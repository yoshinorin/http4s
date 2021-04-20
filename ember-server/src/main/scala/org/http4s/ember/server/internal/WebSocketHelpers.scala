/*
 * Copyright 2019 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.ember.server.internal

import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import cats.data.NonEmptyList
import fs2.{Stream, Chunk, Pull, Pipe}
import fs2.io.tcp._
import org.http4s.syntax.all._
import org.http4s._
import org.http4s.websocket.{WebSocketContext, FrameTranscoder}
import org.http4s.headers._
import org.http4s.ember.core.Read
import org.http4s.ember.core.Util.durationToFinite

import scala.concurrent.duration.Duration
import java.security.MessageDigest
import java.util.Base64
import java.nio.charset.StandardCharsets
import org.http4s.headers.Connection
import org.http4s.websocket.WebSocketFrame
import java.nio.ByteBuffer

object WebSocketHelpers {

  private[this] val rfc6455Magic =
    "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StandardCharsets.US_ASCII)
  private[this] val supportedWebSocketVersion = 13

  private[this] val upgradeCi = "upgrade".ci
  private[this] val webSocketCi = "websocket".ci
  private[this] val connectionUpgrade = Connection(NonEmptyList.of(upgradeCi))
  private[this] val upgradeWebSocket = Upgrade(NonEmptyList.of(webSocketCi))

  // TODO: Express this in terms of Stream to leverage interrupt machinery
  def upgrade[F[_]](
      socket: Socket[F],
      req: Request[F],
      ctx: WebSocketContext[F],
      buffer: Array[Byte],
      receiveBufferSize: Int,
      idleTimeout: Duration,
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit])(implicit
      F: Concurrent[F]): F[Unit] = {
    val wsResponse = clientHandshake(req) match {
      case Right(key) =>
        serverHandshake(key)
          .map { accept =>
            val secWebSocketAccept = `Sec-WebSocket-Accept`(accept)
            val headers =
              ctx.headers ++ Headers.of(connectionUpgrade, upgradeWebSocket, secWebSocketAccept)
            Response[F](Status.SwitchingProtocols)
              .withHeaders(headers)
          }
          .handleError(_ =>
            Response[F](Status.InternalServerError).withEntity(
              "Encountered an error during WebSocket handshake."))
      case Left(error) => 
        // TODO: insert the appropriate headers
        Response[F](error.status).withEntity(error.message).pure[F]
    }

    wsResponse
      .flatMap { res =>
        ServerHelpers.send(socket)(Some(req), res, idleTimeout, onWriteFailure).void
      } >> runConnection(socket, ctx, buffer, receiveBufferSize, idleTimeout, onWriteFailure)
  }

  private def runConnection[F[_]](socket: Socket[F], ctx: WebSocketContext[F], buffer: Array[Byte], receiveBufferSize: Int, idleTimeout: Duration, onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit])(implicit F: Concurrent[F]): F[Unit] = {
    val read: Read[F] = socket.read(receiveBufferSize, durationToFinite(idleTimeout))
    val frameTranscoder = new FrameTranscoder(false)
    
    // TODO: cleanup on connection closure/errors
    // TODO: consider write/read failures and effect on outer connection
    // TODO: there is some shared code here with ServerHelpers
    val writer = ctx.webSocket.send
      .flatMap { frame =>
        // TODO: frameToBuffer can throw
        Stream
          .iterable(frameTranscoder.frameToBuffer(frame).map(buffer => {
            // TODO: improve
            val bytes = new Array[Byte](buffer.remaining())
            buffer.get(bytes)
            Chunk.bytes(bytes)
          }))
          .flatMap(Stream.chunk(_))
      }
      .through(socket.writes(durationToFinite(idleTimeout)))
      .compile
      .drain
      .attempt
      .flatMap {
        case Left(err) =>
          err.printStackTrace()
          F.unit
        case Right(()) => {
          println("Connection ended")
          F.unit
        }
      }

    val reader = (Stream.chunk(Chunk.bytes(buffer)) ++ readStream(read))
      .through(decodeFrames(frameTranscoder))
      .through(ctx.webSocket.receive)
      .compile
      .drain
      .attempt
      .void

    // TODO: will probably want to use concurrently here
    F.background(writer).use { _ =>
      reader
    }
  }

  private def decodeFrames[F[_]](frameTranscoder: FrameTranscoder)(implicit F: Concurrent[F]): Pipe[F, Byte, WebSocketFrame] = stream => {
    def go(rest: Stream[F, Byte], acc: Array[Byte]): Pull[F, WebSocketFrame, Unit] =
      rest.pull.uncons.flatMap { 
        case Some((chunk, next)) => 
          val buffer = acc ++ chunk.toArray
          val byteBuffer = ByteBuffer.wrap(buffer)
          Pull.attemptEval(F.delay(frameTranscoder.bufferToFrame(byteBuffer)))
            .flatMap {
              case Right(value) => {
                // TODO: value is nullable
                val remaining = new Array[Byte](byteBuffer.remaining())
                byteBuffer.get(remaining)
                Pull.output1(value) >> go(next, remaining)
              }
              case Left(err) => 
                // TODO: figure out what to do here
                println(err)
                Pull.done
            }
        case None => 
          // TODO: figure out what to do here
          println("done")
          Pull.done
      }
    
    go(stream, Array.emptyByteArray).void.stream
  }

  private def clientHandshake[F[_]](req: Request[F]): Either[ClientHandshakeError, String] = {
    val connection = req.headers.get(Connection) match {
      case Some(header) if header.values.contains_(upgradeCi) => Right(())
      case _ => Left(UpgradeRequired)
    }

    val upgrade = req.headers.get(Upgrade) match {
      case Some(header) if header.values.contains_(webSocketCi) => Right(())
      case _ => Left(UpgradeRequired)
    }

    val version = req.headers.get(`Sec-WebSocket-Version`) match {
      case Some(header) if header.version == supportedWebSocketVersion => Right(())
      case Some(header) => Left(UnsupportedVersion(supportedWebSocketVersion, header.version))
      case None => Left(VersionNotFound)
    }

    val key = req.headers.get(`Sec-WebSocket-Key`) match {
      case Some(header) => Right(header.value)
      case None => Left(KeyNotFound)
    }

    (connection, upgrade, version, key).mapN { case (_, _, _, key) => key }
  }

  private def serverHandshake[F[_]](value: String)(implicit F: Sync[F]): F[String] = F.delay {
    val crypt = MessageDigest.getInstance("SHA-1")
    crypt.reset()
    crypt.update(value.getBytes(StandardCharsets.US_ASCII))
    crypt.update(rfc6455Magic)
    val bytes = crypt.digest()
    Base64.getEncoder.encodeToString(bytes)
  }

  private def readStream[F[_]](read: Read[F]): Stream[F, Byte] =
    Stream.eval(read).flatMap {
      case Some(bytes) =>
        Stream.chunk(bytes) ++ readStream(read)
      case None => Stream.empty
    }

  sealed abstract class ClientHandshakeError(val status: Status, val message: String)
  case object VersionNotFound
      extends ClientHandshakeError(Status.BadRequest, "Sec-WebSocket-Version header not present.")
  final case class UnsupportedVersion(supported: Int, requested: Int)
      extends ClientHandshakeError(
        Status.UpgradeRequired,
        s"This server only supports WebSocket version $supported.")
  case object UpgradeRequired
      extends ClientHandshakeError(
        Status.UpgradeRequired,
        "Upgrade required for WebSocket communication.")
  case object KeyNotFound
      extends ClientHandshakeError(Status.BadRequest, "Sec-WebSocket-Key header not present.")
}
