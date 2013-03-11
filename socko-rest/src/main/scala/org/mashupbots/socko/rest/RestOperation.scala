//
// Copyright 2013 Vibul Imtarnasan, David Bolton and Socko contributors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package org.mashupbots.socko.rest

import scala.collection.JavaConversions._
import scala.reflect.runtime.{ universe => ru }
import org.mashupbots.socko.infrastructure.Logger

/**
 * Meta data describing REST operation that processes data.
 *
 * A REST operation is uniquely defined by its HTTP method and path.
 *
 * @param method HTTP method
 * @param uriTemplate URI template used for matching incoming REST requests
 *  - The template can be an exact match like `/pets`.
 *  - The template can have a path variable like `/pets/{petId}`. In this case, the template
 *    will match all paths with 2 segments and the first segment being `pets`. The second
 *    segment will be bound to a variable called `petId` using [[org.mashupbots.socko.rest.PathParam]].
 *  - The URI template does NOT support query string.  This is defined using
 *    [[org.mashupbots.socko.rest.QueryParam]].
 * @param actorPath Path to actor to which this request will be sent for processing.
 *  - You can also bind your request to an actor at bootup time using an actor path of `lookup:{key}`.
 *  - The `key` is the key to a map of actor names passed into [[org.mashupbots.socko.rest.RestRegistry]].
 * @param responseClass Class path of the response class.
 *  - If empty, the assumed response class is the same class path and name as the request class;
 *    but with `Request` suffix replaced with `Response`. For `MyRestRequest`, the default response class
 *    that will be used in is `MyRestResponse`.
 * @param name Name provided for the convenience of the UI and client code generator
 *    If empty, the name of the request class will be used without the `Request` prefix.
 * @param description Optional short description. Less than 60 characters is recommended.
 * @param notes Optional long description
 * @param depreciated Flag to indicate if this endpoint is depreciated or not. Defaults to `false`.
 * @param errorResponses Map of HTTP error status codes and reasons
 */
case class RestOperation(
  method: String,
  uriTemplate: String,
  actorPath: String,
  responseClass: String,
  name: String,
  description: String,
  notes: String,
  depreciated: Boolean,
  errorResponses: Map[Int, String]) extends Logger {

  /**
   * The `uriTemplate` split into path segments for ease of matching
   */
  val pathSegments: List[PathSegment] = {
    if (uriTemplate == null || uriTemplate.length == 0)
      throw new IllegalArgumentException("URI cannot be null or empty")

    val s = if (uriTemplate.startsWith("/")) uriTemplate.substring(1) else uriTemplate
    val ss = s.split("/").toList
    val segments = ss.map(s => PathSegment(s))
    segments
  }
}

/**
 * Companion [[org.mashupbots.socko.rest.RestOperation]] object
 */
object RestOperation extends Logger {

  val restGetType = ru.typeOf[RestGet]

  val uriTemplateName = ru.newTermName("uriTemplate")
  val actorPathName = ru.newTermName("actorPath")
  val responseClassName = ru.newTermName("responseClass")
  val nameName = ru.newTermName("name")
  val descriptionName = ru.newTermName("description")
  val notesName = ru.newTermName("notes")
  val depreciatedName = ru.newTermName("depreciated")
  val errorResponsesName = ru.newTermName("errorResponses")

  /**
   * Instance a `RestDeclaration` using information of an annotation
   *
   * @param a A Rest annotation
   * @returns [[org.mashupbots.socko.rest.RestDeclaration]]
   */
  def apply(a: ru.Annotation): RestOperation = {
    val method = if (a.tpe =:= restGetType) {
      "GET"
    } else {
      throw new IllegalStateException("Unknonw annotation type " + a.tpe.toString)
    }

    def getArg[T](n: ru.Name, defaultValue: T): T = {
      if (a.javaArgs.contains(n)) {
        a.javaArgs(n).asInstanceOf[ru.LiteralArgument].value.value.asInstanceOf[T];
      } else {
        defaultValue
      }
    }
    def getStringArrayArg(n: ru.Name, defaultValue: Array[String]): Array[String] = {
      if (a.javaArgs.contains(n)) {
        val aa = a.javaArgs(n).asInstanceOf[ru.ArrayArgument].args
        aa.map(l => l.asInstanceOf[ru.LiteralArgument].value.value.asInstanceOf[String]);
      } else {
        defaultValue
      }
    }

    val uriTemplate = getArg(uriTemplateName, "")
    val actorPath = getArg(actorPathName, "")
    val responseClass = getArg(responseClassName, "")
    val name = getArg(nameName, "")
    val description = getArg(descriptionName, "")
    val notes = getArg(notesName, "")
    val depreciated = getArg(depreciatedName, false)
    val errorResponses = getStringArrayArg(errorResponsesName, Array.empty[String])
    val errorResponsesMap: Map[Int, String] = try {
      errorResponses.map(e => {
        val s = e.split("=")
        (Integer.parseInt(s(0).trim()), s(1).trim())
      }).toMap
    } catch {
      case ex: Throwable => {
        log.error("Error '%s' parsing error response map for '%s %s': (%s). All error responses for this operation will be ignored.".format(
          ex.getMessage, method, uriTemplate, errorResponses.mkString(",")), ex)
        Map.empty
      }
    }

    RestOperation(method, uriTemplate, actorPath, responseClass, name, description, notes, depreciated, errorResponsesMap)
  }

  /**
   * Finds if a rest annotation is in a list of annotations
   *
   * @param annotations List of annotations for a class
   * @returns The first matching rest annotation. `None` if not match
   */
  def findAnnotation(annotations: List[ru.Annotation]): Option[ru.Annotation] = {
    annotations.find(a => a.tpe =:= restGetType);
  }
}

/**
 * Encapsulates a path segment
 *
 * ==Example Usage==
 * {{{
 * // '{Id}'
 * PathSegment("Id", true)
 *
 * // 'user'
 * PathSegment("user", false)
 * }}}
 *
 * @param name Name of the variable or static segment
 * @param isVariable Flag to denote if this segment is variable and is intended to be bound to a variable or not.
 *   If not, it is a static segment
 */
case class PathSegment(
  name: String,
  isVariable: Boolean) {
}

/**
 * Factory to parse a string into a path segment
 */
object PathSegment {
  /**
   * Parses a string into a path segment
   *
   * A string is a variable if it is in the format: `{name}`.  The `name` part will be put in the
   * name field of the path segment
   *
   * @param s string to parse
   */
  def apply(s: String): PathSegment =
    if (s == null || s.length == 0) throw new IllegalArgumentException("Path segment cannot be null or empty")
    else if (s.startsWith("{") && s.endsWith("}")) PathSegment(s.substring(1, s.length - 2), true)
    else PathSegment(s, false)
}

