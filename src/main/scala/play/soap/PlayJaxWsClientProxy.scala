package play.soap

import java.io.{IOException, Closeable}
import java.lang.reflect.{Method, InvocationTargetException}
import java.net.HttpURLConnection
import javax.xml.soap.{SOAPConstants, SOAPException, SOAPFault}
import javax.xml.ws.handler.MessageContext
import javax.xml.ws.handler.MessageContext.Scope
import javax.xml.ws.http.{HTTPException, HTTPBinding}
import javax.xml.ws.soap.{SOAPFaultException, SOAPBinding}
import javax.xml.ws._

import org.apache.cxf.binding.soap.SoapFault
import org.apache.cxf.binding.soap.saaj.{SAAJUtils, SAAJFactoryResolver}
import org.apache.cxf.common.i18n.Message
import org.apache.cxf.common.logging.LogUtils
import org.apache.cxf.common.util.StringUtils
import org.apache.cxf.endpoint.{ClientCallback, Endpoint, Client}
import org.apache.cxf.frontend.ClientProxy
import org.apache.cxf.helpers.CastUtils
import org.apache.cxf.interceptor.Fault
import org.apache.cxf.jaxws.EndpointReferenceBuilder
import org.apache.cxf.jaxws.context.WrappedMessageContext
import org.apache.cxf.jaxws.support.JaxWsEndpointImpl
import org.apache.cxf.service.invoker.MethodDispatcher

import java.util.{Map => JMap, Locale}

import org.apache.cxf.service.model.BindingOperationInfo
import org.w3c.dom.Node

import scala.concurrent.{Promise, Future}
import scala.util.Try

private[soap] object PlayJaxWsClientProxy {
  val log = LogUtils.getL7dLogger(classOf[PlayJaxWsClientProxy])

  @throws(classOf[SOAPException])
  def createSoapFault(binding: SOAPBinding, ex: Exception): SOAPFault = {
    Try(binding.getSOAPFactory.createFault)
      .orElse(Try(binding.getMessageFactory.createMessage.getSOAPPart.getEnvelope.getBody.addFault()))
      .toOption.map { soapFault1 =>
        ex match {
          case sf: SoapFault =>
            val soapFault = if (!(soapFault1.getNamespaceURI() == sf.getFaultCode.getNamespaceURI) &&
              (SOAPConstants.URI_NS_SOAP_1_1_ENVELOPE == sf.getFaultCode.getNamespaceURI)) {
              Try(SAAJFactoryResolver.createSOAPFactory(null).createFault).getOrElse(soapFault1)
            } else soapFault1

            val isSoap11 = SOAPConstants.URI_NS_SOAP_1_1_ENVELOPE == soapFault.getNamespaceURI()

            if (StringUtils.isEmpty(sf.getLang)) {
              soapFault.setFaultString(sf.getReason)
            } else {
              soapFault.setFaultString(sf.getReason, stringToLocale(sf.getLang))
            }

            SAAJUtils.setFaultCode(soapFault, sf.getFaultCode)

            val role = sf.getRole
            if (role != null) {
              soapFault.setFaultActor(role)
            }

            if (sf.getSubCodes != null && !isSoap11) {
              import scala.collection.JavaConversions._
              for (fsc <- sf.getSubCodes) {
                soapFault.appendFaultSubcode(fsc)
              }
            }

            if (sf.hasDetails) {
              var nd: Node = soapFault.getOwnerDocument.importNode(sf.getDetail, true)
              nd = nd.getFirstChild
              soapFault.addDetail()
              while (nd != null) {
                val next: Node = nd.getNextSibling
                soapFault.getDetail.appendChild(nd)
                nd = next
              }
            }

            soapFault
          case _ =>
            val msg = ex.getMessage
            if (msg != null) {
              soapFault1.setFaultString(msg)
            }

            soapFault1
      }
    }.orNull
  }

  def stringToLocale(locale: String): Locale = {
    val parts = locale.split("-", 0)
    if (parts.length == 1) {
      new Locale(parts(0))
    } else if (parts.length == 2) {
      new Locale(parts(0), parts(1))
    } else {
      new Locale(parts(0), parts(1), parts(2))
    }
  }
}

class PlayJaxWsClientProxy(c: Client, binding: Binding) extends ClientProxy(c) with BindingProvider {

  setupEndpointAddressContext(getClient.getEndpoint)
  private val builder = new EndpointReferenceBuilder(getClient.getEndpoint.asInstanceOf[JaxWsEndpointImpl])

  import PlayJaxWsClientProxy._

  override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = {
    if (client == null) {
      throw new IllegalStateException("The client has been closed.")
    }
    val endpoint = getClient.getEndpoint
    val address = endpoint.getEndpointInfo.getAddress
    val dispatcher = endpoint.getService.get(classOf[MethodDispatcher].getName).asInstanceOf[MethodDispatcher]

    val params = if (args == null) Array.empty[AnyRef] else args

    // Not sure if we need to support this, but copied from JaxwsClientProxy
    try {
      if ((method.getDeclaringClass == classOf[BindingProvider]) || (method.getDeclaringClass == classOf[AnyRef]) || (method.getDeclaringClass == classOf[Closeable])) {
        return method.invoke(this, params)
      } else if (method.getDeclaringClass.isInstance(client)) {
        return method.invoke(client, params)
      }
    } catch {
      case e: InvocationTargetException => throw e.getCause
    }

    val oi = dispatcher.getBindingOperation(method, endpoint)
    if (oi == null) {
      throw new WebServiceException(new Message("NO_BINDING_OPERATION_INFO", log, method.getName).toString)
    }

    client.getRequestContext.put(classOf[Method].getName, method)
    val result = try {
        invokeAsync(method, oi, params)
    } catch {
      case wex: WebServiceException => throw wex
      case ex: Exception =>
        for (excls <- method.getExceptionTypes) {
          if (excls.isInstance(ex)) {
            throw ex
          }
        }
        if (ex.isInstanceOf[Fault] && ex.getCause.isInstanceOf[IOException]) {
          throw new WebServiceException(ex.getMessage, ex.getCause)
        }
        getBinding match {
          case http: HTTPBinding =>
            val exception = new HTTPException(HttpURLConnection.HTTP_INTERNAL_ERROR)
            exception.initCause(ex)
            throw exception
          case soap: SOAPBinding =>
            val soapFault = createSoapFault(getBinding.asInstanceOf[SOAPBinding], ex)
            if (soapFault == null) {
              throw new WebServiceException(ex)
            }
            val exception = new SOAPFaultException(soapFault)
            if (ex.isInstanceOf[Fault] && ex.getCause != null) {
              exception.initCause(ex.getCause)
            }
            else {
              exception.initCause(ex)
            }
            throw exception
          case _ =>
            throw new WebServiceException(ex)
        }
    } finally {
      if (addressChanged(address)) {
        setupEndpointAddressContext(getClient.getEndpoint)
      }
    }

    val respContext = client.getResponseContext
    val scopes = CastUtils.cast(respContext.get(WrappedMessageContext.SCOPES).asInstanceOf[JMap[_, _]])
    if (scopes != null) {
      import scala.collection.JavaConversions._
      for (scope <- scopes.entrySet) {
        if (scope.getValue == Scope.HANDLER) {
          respContext.remove(scope.getKey)
        }
      }
    }
    adjustObject(result)
  }

  private def invokeAsync(method: Method, oi: BindingOperationInfo, params: Array[AnyRef]): Future[AnyRef] = {
    client.setExecutor(getClient.getEndpoint.getExecutor)

    val promise = Promise[AnyRef]()
    val callback = new PlayJaxwsClientCallback(promise)
    client.invoke(callback, oi, params: _*)
    promise.future
  }

  def getBinding = binding

  private def addressChanged(address: String): Boolean = {
    !(address == null ||
      getClient.getEndpoint.getEndpointInfo == null ||
      (address == getClient.getEndpoint.getEndpointInfo.getAddress))
  }

  private def setupEndpointAddressContext(endpoint: Endpoint): Unit = {
    if (endpoint != null && endpoint.getEndpointInfo.getAddress != null) {
      getRequestContext.put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, endpoint.getEndpointInfo.getAddress)
    }
  }

  override def getRequestContext: MessageContext = {
    new WrappedMessageContext(this.getClient.getRequestContext, null, Scope.APPLICATION)
  }

  override def getResponseContext = {
    new WrappedMessageContext(this.getClient.getResponseContext, null, Scope.APPLICATION)
  }

  def getEndpointReference: EndpointReference = {
    builder.getEndpointReference
  }

  def getEndpointReference[T <: EndpointReference](clazz: Class[T]): T = {
    builder.getEndpointReference(clazz)
  }
}
