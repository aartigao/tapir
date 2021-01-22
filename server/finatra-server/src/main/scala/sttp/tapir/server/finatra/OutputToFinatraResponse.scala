package sttp.tapir.server.finatra

import java.io.InputStream
import java.nio.charset.Charset
import com.twitter.finagle.http.{Response, Status, Version}
import com.twitter.io.{Buf, InputStreamReader, Reader}
import com.twitter.util.Future
import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.content._
import org.apache.http.entity.mime.{FormBodyPart, FormBodyPartBuilder, MultipartEntityBuilder}
import sttp.model.{Header, Part}
import sttp.tapir.EndpointOutput.WebSocketBody
import sttp.tapir.server.internal.{EncodeOutputBody, EncodeOutputs, OutputValues}
import sttp.tapir.{CodecFormat, EndpointOutput, RawBodyType}
import sttp.tapir.internal._

object OutputToFinatraResponse {
  private val encodeOutputs: EncodeOutputs[Future, (FinatraContent, String), Nothing, Nothing] =
    new EncodeOutputs[Future, (FinatraContent, String), Nothing, Nothing](
      new EncodeOutputBody[(FinatraContent, String), Nothing, Nothing] {
        override val streams: NoStreams = NoStreams
        override def rawValueToBody[R](v: R, format: CodecFormat, bodyType: RawBodyType[R]): (FinatraContent, String) =
          rawValueToFinatraContent(bodyType.asInstanceOf[RawBodyType[Any]], formatToContentType(format, charset(bodyType)), v)
        override def streamValueToBody(v: Nothing, format: CodecFormat, charset: Option[Charset]): (FinatraContent, String) = {
          v //impossible
        }
        override def webSocketPipeToBody[REQ, RESP](
            pipe: Nothing,
            o: WebSocketBody[streams.Pipe[REQ, RESP], REQ, RESP, _, Nothing]
        ): Nothing =
          pipe
      }
    )(FutureMonadError)

  def apply[O](
      defaultStatus: Status,
      output: EndpointOutput[O, _],
      v: Any
  ): Future[Response] = {
    encodeOutputs(output, ParamsAsAny(v), OutputValues.empty).map(outputValuesToResponse(_, defaultStatus))
  }

  private def outputValuesToResponse(outputValues: OutputValues[(FinatraContent, String), Nothing], defaultStatus: Status): Response = {
    val status = outputValues.statusCode.map(sc => Status(sc.code)).getOrElse(defaultStatus)

    val responseWithContent = outputValues.body match {
      case Some(finatraContentEither) =>
        val (fContent, ct) = finatraContentEither.merge
        val response = fContent match {
          case FinatraContentBuf(buf) =>
            val r = Response(Version.Http11, status)
            r.content = buf
            r
          case FinatraContentReader(reader) => Response(Version.Http11, status, reader)
        }
        response.contentType = ct
        response
      case None =>
        Response(Version.Http11, status)
    }

    outputValues.headers.foreach { case (name, value) => responseWithContent.headerMap.add(name, value) }

    // If there's a content-type header in headers, override the content-type.
    outputValues.headers.find(_._1.toLowerCase == "content-type").foreach { case (_, value) =>
      responseWithContent.contentType = value
    }

    responseWithContent
  }

  private def rawValueToFinatraContent[CF <: CodecFormat, R](bodyType: RawBodyType[R], ct: String, r: R): (FinatraContent, String) = {
    bodyType match {
      case RawBodyType.StringBody(charset) =>
        FinatraContentBuf(Buf.ByteArray.Owned(r.toString.getBytes(charset))) -> ct
      case RawBodyType.ByteArrayBody  => FinatraContentBuf(Buf.ByteArray.Owned(r)) -> ct
      case RawBodyType.ByteBufferBody => FinatraContentBuf(Buf.ByteBuffer.Owned(r)) -> ct
      case RawBodyType.InputStreamBody =>
        FinatraContentReader(Reader.fromStream(r)) -> ct
      case RawBodyType.FileBody =>
        FinatraContentReader(Reader.fromFile(r)) -> ct
      case m: RawBodyType.MultipartBody =>
        val entity = MultipartEntityBuilder.create()

        r.flatMap(rawPartToFormBodyPart(m, _)).foreach { formBodyPart: FormBodyPart => entity.addPart(formBodyPart) }

        // inputStream is split out into a val because otherwise it doesn't compile in 2.11
        val inputStream: InputStream = entity.build().getContent

        FinatraContentReader(InputStreamReader(inputStream)) -> ct
    }
  }

  private def rawValueToContentBody[CF <: CodecFormat, R](bodyType: RawBodyType[R], part: Part[R], r: R): ContentBody = {
    val contentType: String = part.header("content-type").getOrElse("text/plain")

    bodyType match {
      case RawBodyType.StringBody(charset) =>
        new StringBody(r.toString, ContentType.create(contentType, charset))
      case RawBodyType.ByteArrayBody =>
        new ByteArrayBody(r, ContentType.create(contentType), part.fileName.get)
      case RawBodyType.ByteBufferBody =>
        val array: Array[Byte] = new Array[Byte](r.remaining)
        r.get(array)
        new ByteArrayBody(array, ContentType.create(contentType), part.fileName.get)
      case RawBodyType.FileBody =>
        part.fileName match {
          case Some(filename) => new FileBody(r, ContentType.create(contentType), filename)
          case None           => new FileBody(r, ContentType.create(contentType))
        }
      case RawBodyType.InputStreamBody =>
        new InputStreamBody(r, ContentType.create(contentType), part.fileName.get)
      case _: RawBodyType.MultipartBody =>
        throw new UnsupportedOperationException("Nested multipart messages are not supported.")
    }
  }

  private def rawPartToFormBodyPart[R](m: RawBodyType.MultipartBody, part: Part[R]): Option[FormBodyPart] = {
    m.partType(part.name).map { partType =>
      val builder = FormBodyPartBuilder
        .create(
          part.name,
          rawValueToContentBody(partType.asInstanceOf[RawBodyType[Any]], part.asInstanceOf[Part[Any]], part.body)
        )

      part.headers.foreach { case Header(name, value) => builder.addField(name, value) }

      builder.build()
    }
  }

  private def formatToContentType(format: CodecFormat, charset: Option[Charset]): String = {
    format match {
      case CodecFormat.Json()               => format.mediaType.toString()
      case CodecFormat.OctetStream()        => format.mediaType.toString()
      case CodecFormat.XWwwFormUrlencoded() => format.mediaType.toString()
      case CodecFormat.MultipartFormData()  => format.mediaType.toString()
      // text/plain and others
      case _ =>
        val mt = format.mediaType
        charset.map(c => mt.charset(c.toString)).getOrElse(mt).toString
    }
  }
}
