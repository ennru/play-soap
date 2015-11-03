/*
 * Copyright © 2015 Typesafe, Inc. All rights reserved.
 * No information contained herein may be reproduced or transmitted in any form or
 * by any means without the express written permission of Typesafe, Inc.
 */
package play.soap.sbtplugin

import play.soap.sbtplugin.Imports.WsdlKeys.FutureApi

/**
 * A helper used by [[PlaySEIGenerator]] to generate the correct future type to wrap a Java type.
 * @param futureApi
 */
class FutureGenerator(futureApi: FutureApi) {

  /** Pregenerated type mappings */
  private val mapping: Map[String, String] = Map(
    "void" -> futureApi.voidType,
    "boolean" -> "java.lang.Boolean",
    "byte" -> "java.lang.Byte",
    "char" -> "java.lang.Character",
    "double" -> "java.lang.Double",
    "float" -> "java.lang.Float",
    "int" -> "java.lang.Integer",
    "long" -> "java.lang.Long",
    "short" -> "java.lang.Short"
  )

  /**
   * Generate the correct future type that holds the given Java type. E.g. for a Java type of
   * "void" this could be "scala.concurrent.Future<scala.Unit>" or "play.libs.F.Promise<Void>".
   * For a Java type of "int" it could be "scala.concurrent.Future<java.lang.Integer>" or
   * "play.libs.F.Promise<java.lang.Integer>"
   *
   * @param javaType The Java type to wrap.
   */
  def futureType(javaType: String) = {
    val elementType = mapping.getOrElse(javaType, javaType)
    futureApi.fqn + "<" + elementType + ">"
  }
}
