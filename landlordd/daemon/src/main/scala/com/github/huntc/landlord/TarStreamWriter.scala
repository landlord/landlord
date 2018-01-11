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
 * Functions to write out tar streams.
 *
 * TarArchiveInputStream doesn't appear to be very fast in conjunction with its dependency
 * on InputStream. We could probably improve performance drammatically if we eliminated
 * both from the picture I wrote our own tar stream parser. I'm unsure if it is worth it
 * right now though.
 */
object TarStreamWriter {
  private[landlord] def writeTarStream(
    source: Source[ByteString, AnyRef],
    rootPath: Path,
    blockingEc: ExecutionContext
  )(implicit mat: Materializer): Future[Unit] = {

    val TarRecordSize = 512
    val TarBlockingFactor = 20
    val TarBufferSize = TarRecordSize * TarBlockingFactor * 2
    val TarInputMaxBlockingTime = 3.seconds

    val FileBufferSize = 8192

    rootPath.toFile.mkdirs()

    Future {
      val is =
        new BufferedInputStream(source.runWith(StreamConverters.asInputStream(TarInputMaxBlockingTime)), TarBufferSize)
      try {
        val tarInput = new ArchiveStreamFactory().createArchiveInputStream(is).asInstanceOf[TarArchiveInputStream]
        try {
          val tarInputBuffer = Array.ofDim[Byte](TarBufferSize)
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
                val os = new BufferedOutputStream(Files.newOutputStream(path), FileBufferSize)
                try {
                  @tailrec def foreachRead(offset: Int)(writeOp: (Array[Byte], Int, Int) => Unit): Unit = {
                    val remaining = FileBufferSize - offset
                    if (remaining > 0) {
                      val read = tarInput.read(tarInputBuffer, offset, remaining)
                      if (read > -1)
                        foreachRead(offset + read)(writeOp)
                      else
                        writeOp(tarInputBuffer, 0, offset)
                    } else {
                      writeOp(tarInputBuffer, 0, FileBufferSize)
                      foreachRead(0)(writeOp)
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