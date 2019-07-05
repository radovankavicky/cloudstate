/*
 * Copyright 2019 Lightbend Inc.
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

package com.lightbend.statefulserverless

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import akka.grpc.scaladsl.{GrpcExceptionHandler, GrpcMarshalling}
import GrpcMarshalling.{marshalStream, unmarshalStream}
import akka.grpc.{Codecs, ProtobufSerializer, GrpcServiceException}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.actor.ActorSystem
import akka.util.ByteString
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.google.protobuf.Descriptors.FileDescriptor
import com.lightbend.statefulserverless.grpc._
import akka.NotUsed
import _root_.grpc.reflection.v1alpha._

object Reflection {
  private final val ReflectionPath = Path / ServerReflection.name / "ServerReflectionInfo"

  def serve(fileDesc: FileDescriptor)(implicit mat: Materializer, sys: ActorSystem): PartialFunction[HttpRequest, Future[HttpResponse]] = {
    implicit val ec: ExecutionContext = mat.executionContext
    import ServerReflection.Serializers._

    val handler = handle(fileDesc)

    {
      case req: HttpRequest if req.uri.path == ReflectionPath =>
        val responseCodec = Codecs.negotiate(req)
        GrpcMarshalling.unmarshalStream(req)(ServerReflectionRequestSerializer, mat)
        .map(_ via handler)
        .map(e => GrpcMarshalling.marshalStream(e, GrpcExceptionHandler.defaultMapper)(ServerReflectionResponseSerializer, mat, responseCodec, sys))
    }
  }

  private final def findFileDescForName(name: String, fileDesc: FileDescriptor): Option[FileDescriptor] =
    if (name == fileDesc.getName) Option(fileDesc)
    else fileDesc.getDependencies.iterator.asScala.map(fd => findFileDescForName(name, fd)).find(_.isDefined).flatten

  private final def containsSymbol(symbol: String, fileDesc: FileDescriptor): Boolean =
    (symbol.startsWith(fileDesc.getPackage)) && // Ensure package match first
    (Names.splitNext(if (fileDesc.getPackage.isEmpty) symbol else symbol.drop(fileDesc.getPackage.length + 1)) match {
      case ("", "") => false
      case (typeOrService, "") =>
      //fileDesc.findEnumTypeByName(typeOrService) != null || // TODO investigate if this is expected
        fileDesc.findMessageTypeByName(typeOrService) != null ||
        fileDesc.findServiceByName(typeOrService) != null
      case (service, method) =>
        Option(fileDesc.findServiceByName(service)).exists(_.findMethodByName(method) != null)
    })

  private final def findFileDescForSymbol(symbol: String, fileDesc: FileDescriptor): Option[FileDescriptor] =
    if (containsSymbol(symbol, fileDesc)) Option(fileDesc)
    else fileDesc.getDependencies.iterator.asScala.map(fd => findFileDescForSymbol(symbol, fd)).find(_.isDefined).flatten

  private final def containsExtension(container: String, number: Int, fileDesc: FileDescriptor): Boolean =
    fileDesc.getExtensions.iterator.asScala.exists(ext => container == ext.getContainingType.getFullName && number == ext.getNumber)

  private final def findFileDescForExtension(container: String, number: Int, fileDesc: FileDescriptor): Option[FileDescriptor] =
    if (containsExtension(container, number, fileDesc)) Option(fileDesc)
    else fileDesc.getDependencies.iterator.asScala.map(fd => findFileDescForExtension(container, number, fd)).find(_.isDefined).flatten

  private final def findExtensionNumbersForContainingType(container: String, fileDesc: FileDescriptor): List[Int] = 
    fileDesc.getDependencies.iterator.asScala.foldLeft(
      fileDesc.getExtensions.iterator.asScala.collect({ case ext if ext.getFullName == container => ext.getNumber }).toList
    )((list, fd) => findExtensionNumbersForContainingType(container, fd) ::: list)

  private def handle(fileDesc: FileDescriptor): Flow[ServerReflectionRequest, ServerReflectionResponse, NotUsed] =
    Flow[ServerReflectionRequest].map(req => {
      import ServerReflectionRequest.{ MessageRequest => In}
      import ServerReflectionResponse.{ MessageResponse => Out}

      val response = req.messageRequest match {
        case In.Empty =>
          Out.Empty
        case In.FileByFilename(fileName) =>
          val list = findFileDescForName(fileName, fileDesc).map(_.toProto.toByteString).toList
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.FileContainingSymbol(symbol) =>
          val list = findFileDescForSymbol(symbol, fileDesc).map(_.toProto.toByteString).toList
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.FileContainingExtension(ExtensionRequest(container, number)) =>
          val list = findFileDescForExtension(container, number, fileDesc).map(_.toProto.toByteString).toList
          Out.FileDescriptorResponse(FileDescriptorResponse(list))
        case In.AllExtensionNumbersOfType(container) =>
          val list = findExtensionNumbersForContainingType(container, fileDesc) // TODO should we throw a NOT_FOUND if we don't know the container type at all?
          Out.AllExtensionNumbersResponse(ExtensionNumberResponse(container, list))
        case In.ListServices(_) =>
          val list = fileDesc.getServices.iterator.asScala.map(s => ServiceResponse(s.getFullName)).toList
          Out.ListServicesResponse(ListServiceResponse(list))
      }
      // TODO Validate assumptions here
      ServerReflectionResponse(req.host, Some(req), response)
    })
}