package fs2.ftp

import java.io.{ FileNotFoundException, InputStream }
import cats.effect.{ Async, Resource }
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.monadError._
import fs2.{ Pipe, Stream }
import org.apache.commons.net.ftp.{ FTP, FTPClient => JFTPClient, FTPSClient => JFTPSClient }
import FtpSettings.UnsecureFtpSettings
import cats.effect.kernel.Sync

final private class UnsecureFtp[F[_]: Async](
  unsafeClient: UnsecureFtp.Client
) extends FtpClient[F, JFTPClient] {

  def stat(path: String): F[Option[FtpResource]] =
    execute(client => Option(client.mlistFile(path)).map(FtpResource(_)))

  def readFile(path: String, chunkSize: Int = 2048): fs2.Stream[F, Byte] = {
    val terminate: F[Unit] = execute(_.completePendingCommand())
      .flatMap(
        Sync[F].raiseUnless(_)(
          FileTransferIncompleteError(s"Cannot finalize the file transfer and completely read the entire file $path.")
        )
      )

    val is: F[InputStream] = execute(client => Option(client.retrieveFileStream(path)))
      .flatMap(opt =>
        opt.fold(Sync[F].raiseError[InputStream](new FileNotFoundException(s"file doesnt exist $path")))(Sync[F].pure)
      )

    fs2.io.readInputStream(is, chunkSize) ++ (fs2.Stream.eval(terminate) >> fs2.Stream.empty)
  }

  def rm(path: String): F[Unit] =
    execute(_.deleteFile(path))
      .ensure(InvalidPathError(s"Path is invalid. Cannot delete file : $path"))(identity)
      .map(_ => ())

  def rmdir(path: String): F[Unit] =
    execute(_.removeDirectory(path))
      .ensure(InvalidPathError(s"Path is invalid. Cannot remove directory : $path"))(identity)
      .map(_ => ())

  def mkdir(path: String): F[Unit] =
    execute(_.makeDirectory(path))
      .ensure(InvalidPathError(s"Path is invalid. Cannot create directory : $path"))(identity)
      .map(_ => ())

  def ls(path: String): Stream[F, FtpResource] =
    fs2.Stream
      .evalSeq(execute(_.listFiles(path).toList))
      .map(FtpResource(_, Some(path)))

  def lsDescendant(path: String): Stream[F, FtpResource] =
    fs2.Stream
      .evalSeq(execute(_.listFiles(path).toList))
      .flatMap { f =>
        if (f.isDirectory) {
          val dirPath = Option(path).filter(_.endsWith("/")).fold(s"$path/${f.getName}")(p => s"$p${f.getName}")
          lsDescendant(dirPath)
        } else
          Stream(FtpResource(f, Some(path)))
      }

  def upload(path: String): Pipe[F, Byte, Unit] =
    source =>
      source
        .through(fs2.io.toInputStream[F])
        .evalMap(is =>
          execute(_.storeFile(path, is))
            .ensure(InvalidPathError(s"Path is invalid. Cannot upload data to : $path"))(identity)
            .void
        )

  def execute[T](f: JFTPClient => T): F[T] =
    Sync[F].blocking(f(unsafeClient))
}

object UnsecureFtp {

  type Client = JFTPClient

  def connect[F[_]: Async](
    settings: UnsecureFtpSettings
  ): Resource[F, FtpClient[F, UnsecureFtp.Client]] =
    for {
      r <- Resource.make[F, FtpClient[F, UnsecureFtp.Client]] {
            Async[F]
              .delay {
                val ftpClient = if (settings.ssl) new JFTPSClient() else new JFTPClient()
                settings.proxy.foreach(ftpClient.setProxy)
                ftpClient.connect(settings.host, settings.port)

                val success = ftpClient.login(settings.credentials.username, settings.credentials.password)

                if (settings.binary) {
                  ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
                }

                ftpClient.setConnectTimeout(settings.connectTimeOut)
                ftpClient.setDefaultTimeout(settings.timeOut)

                if (settings.passiveMode) {
                  ftpClient.enterLocalPassiveMode()
                }

                success -> new UnsecureFtp[F](ftpClient)
              }
              .ensure(ConnectionError(s"Fail to connect to server ${settings.host}:${settings.port}"))(_._1)
              .map(_._2)
          } { client =>
            for {
              connected <- client.execute(_.isConnected)
              _ <- if (!connected) Async[F].unit
                  else
                    client
                      .execute(_.logout)
                      .attempt
                      .flatMap(_ => client.execute(_.disconnect))
            } yield ()
          }
    } yield r
}
