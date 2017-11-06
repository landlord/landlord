package com.github.huntc.landlord

import akka.stream.Materializer
import akka.stream.scaladsl.{ Source, StreamConverters }
import akka.util.ByteString
import java.io.{ BufferedInputStream, BufferedOutputStream }
import java.nio.file.{ attribute, Files, Path }
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.tar.{ TarArchiveEntry, TarArchiveInputStream }
import scala.concurrent.{ blocking, ExecutionContext, Future }
import scala.concurrent.duration._
import scala.annotation.tailrec

/**
 * Functions to write out tar streams
 */
object TarStreamWriter {
  private[landlord] def writeTarStream(
    source: Source[ByteString, AnyRef],
    rootPath: Path,
    blockingEc: ExecutionContext
  )(implicit mat: Materializer): Future[Unit] = {

    val BufferSize = 8192
    val MaxBlockingTime = 3.seconds

    Future {
      val is = new BufferedInputStream(source.runWith(StreamConverters.asInputStream(MaxBlockingTime)))
      try {
        val tarInput = new ArchiveStreamFactory().createArchiveInputStream(is).asInstanceOf[TarArchiveInputStream]
        try {
          val buffer = Array.ofDim[Byte](BufferSize)
          blocking {
            @tailrec def foreachTarEntry(op: TarArchiveEntry => Unit): Unit =
              tarInput.getNextTarEntry match {
                case null =>
                  ()
                case entry =>
                  op(entry)
                  foreachTarEntry(op)
              }
            foreachTarEntry { entry =>
              val path = rootPath.resolve(entry.getName)
              if (entry.isDirectory) {
                path.toFile.mkdirs()
              } else {
                val os = new BufferedOutputStream(Files.newOutputStream(path))
                try {
                  @tailrec def foreachRead(offset: Int)(op: Array[Byte] => Unit): Unit = {
                    val remaining = buffer.length - offset
                    if (remaining > 0) {
                      val read = tarInput.read(buffer, offset, remaining)
                      if (read > -1)
                        foreachRead(offset + read)(op)
                      else
                        op(buffer.take(offset))
                    } else {
                      op(buffer)
                      foreachRead(0)(op)
                    }
                  }
                  foreachRead(0)(os.write)
                } finally {
                  os.close()
                }
                Files.setLastModifiedTime(path, attribute.FileTime.fromMillis(entry.getModTime.getTime))
                // TODO: Set ownership and permissions.
              }
            }
          }
        } finally {
          tarInput.close()
        }
      } finally {
        is.close()
      }
    }(blockingEc)
  }
}