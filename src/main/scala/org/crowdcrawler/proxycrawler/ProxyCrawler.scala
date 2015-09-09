package org.crowdcrawler.proxycrawler

import java.io.IOException
import java.net.URI
import java.security.cert.X509Certificate

import com.typesafe.scalalogging.Logger
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClients
import org.apache.http.ssl.{TrustStrategy, SSLContexts}
import org.apache.http.conn.ssl.{NoopHostnameVerifier, SSLConnectionSocketFactory}
import org.apache.http.util.EntityUtils
import org.crowdcrawler.proxycrawler.checker.ProxyChecker
import org.crowdcrawler.proxycrawler.plugins.AbstractPlugin
import scala.collection.immutable

import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.http.HttpHeaders
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import scala.collection.mutable


class ProxyCrawler(plugins: List[AbstractPlugin]) {
  /** Existed URIs. */
  private val existed = mutable.Set.empty[URI]


  def crawl(): List[ProxyInfo] = {
    val result = mutable.ListBuffer.empty[ProxyInfo]
    for (plugin <- plugins) {
      ProxyCrawler.LOGGER.info("Plugin " + plugin.getClass.getName + " started.")
      result ++= crawl(plugin)
      ProxyCrawler.LOGGER.info("Plugin " + plugin.getClass.getName + " finished.")
    }
    result.toList
  }

  private def crawl(plugin: AbstractPlugin): List[ProxyInfo] = {
    val result = mutable.ListBuffer.empty[ProxyInfo]
    val uris = mutable.Queue.empty[URI]
    val seeds = plugin.seeds
    uris ++= seeds

    while (uris.nonEmpty) {
      val uri = uris.dequeue()
      if (!existed.contains(uri)) {
        existed.add(uri)
        try {
          ProxyCrawler.LOGGER.info("Crawling " + uri.toString)
          val response = ProxyCrawler.CLIENT.execute(ProxyCrawler.createRequest(uri, plugin.customizedHeaders))
          val statusCode = response.getStatusLine.getStatusCode
          if (statusCode == 200) {
            val html = EntityUtils.toString(response.getEntity, plugin.responseCharset)

            result ++= plugin.extract(html).map(p => ProxyInfo(p.host, p.port, p.schema, p.speed, p.location, uri))

            val nextURIs = plugin.next(html)
            nextURIs.filter(p => !existed.contains(p)).foreach(p => uris.enqueue(p))
          } else {
            ProxyCrawler.LOGGER.warn("statusCode = " + statusCode + ", " + uri + " skipped")
          }
        } catch {
          case e: IOException =>
            ProxyCrawler.LOGGER.error("Error crawling " + uri.toString + ", skipped", e)
        }
      }
    }
    result.toList
  }

}

object ProxyCrawler {
  val OBJECT_MAPPER = new ObjectMapper() with ScalaObjectMapper
  OBJECT_MAPPER.registerModule(DefaultScalaModule)

  val DEFAULT_HEADERS = immutable.Map((HttpHeaders.USER_AGENT, "Mozilla/5.0 (Macintosh; Intel Mac" +
    " OS X 10_10_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/44.0.2403.130 Safari/537.36"),
    (HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;" +
      "q=0.9,image/webp,*/*;q=0.8"),
    (HttpHeaders.ACCEPT_ENCODING, "gzip, deflate, sdch"),
    (HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.8,zh-CN;q=0.6,zh;q=0.4"),
    (HttpHeaders.CONNECTION, "keep-alive")
  )

  private val LOGGER = Logger(LoggerFactory.getLogger(classOf[ProxyCrawler]))

  private val CLIENT = {
    // trust all certificates including self-signed certificates
    val sslContext = SSLContexts.custom().loadTrustMaterial(null, new TrustStrategy() {
      def isTrusted(chain: Array[X509Certificate], authType: String) = true
    }).build()
    val connectionFactory = new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE)
    HttpClients.custom().setSSLSocketFactory(connectionFactory).build()
  }

  def apply(classNames: String*): ProxyCrawler = {
    val plugins = mutable.ListBuffer.empty[AbstractPlugin]
    for (className <- classNames) {
      val clazz = Class.forName("org.crowdcrawler.proxycrawler.plugins." + className)
      plugins += clazz.newInstance().asInstanceOf[AbstractPlugin]
    }
    new ProxyCrawler(plugins.toList)
  }

  private def createRequest(uri: URI, headers: immutable.Map[String, String]): HttpGet = {
    val request = new HttpGet(uri)
    for (header <- headers) {
      request.setHeader(header._1, header._2)
    }
    request
  }


  def main(args: Array[String]): Unit = {
    val usage = "Usage: \n\tcrawl [pluginClassName]* OutputFile\n" +
      "\tcheck proxies.json valid_proxies.json\n" +
      "\tfilter valid_proxies.json <HTTP|HTTPS|SOCKS> output.json\n" +
      "For example:\n" +
      "\t1. Crawl all supported websites and save proxies to proxies.json\n" +
      "\t\tcrawl proxies.json\n" +
      "\t2. Crawl www.cnproxy.com and save proxies to proxies.json:\n" +
      "\t\tcrawl CnProxyComPlugin proxies.json\n" +
      "\t3. Check the speed of proxies.\n" +
      "\t\tcheck proxies.json valid_proxies.json\n" +
      "\t4. Filter proxies by schema\n" +
      "\t\tfilter valid_proxies.json HTTP http.json\n"
    if (args.length < 2) {
      println(usage)
      return
    }

    val start = System.currentTimeMillis
    if (args(0) == "crawl") {
      val classNames = if (args.length == 2) {
        Array("CnProxyComPlugin", "CoolProxyNetPlugin", "GatherproxyComPlugin", "IpcnOrgPlugin",
          "ProxyListOrg", "SocksProxyNet", "USProxyOrgPlugin")
      } else {
        args.slice(1, args.length-1)
      }
      val crawler = ProxyCrawler(classNames: _*)

      val proxies = crawler.crawl()

      LOGGER.info("Writing to disk, " + proxies.size + " proxies")
      val json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter.writeValueAsString(proxies)
      Files.write(Paths.get(args.last), json.getBytes(StandardCharsets.UTF_8))
    } else if (args(0) == "check") {
      val json = io.Source.fromFile(args(1), "utf-8").mkString
      val list = OBJECT_MAPPER.readValue[List[ProxyInfo]](json)

      // sort by speed desc
      val validProxies = ProxyChecker.check(list).filter(_.speed > 0)
        .sortWith((p1, p2) => p1.speed > p2.speed)
      LOGGER.info("Writing to disk, " + validProxies.size + " valid proxies out of " + list.size + " proxies")
      val newJson = ProxyCrawler.OBJECT_MAPPER.writerWithDefaultPrettyPrinter
        .writeValueAsString(validProxies)
      Files.write(Paths.get(args(2)), newJson.getBytes(StandardCharsets.UTF_8))

    } else if (args(0) == "filter") {
      val json = io.Source.fromFile(args(1), "utf-8").mkString
      val list = OBJECT_MAPPER.readValue[List[ProxyInfo]](json)
      val filtered = if (args(2) == "SOCKS") {
        list.filter(p => p.schema == "SOCKS" | p.schema == "SOCKS4" || p.schema == "SOCKS5")
      } else {
        list.filter(p => p.schema == args(2))
      }

      val newJson = ProxyCrawler.OBJECT_MAPPER.writerWithDefaultPrettyPrinter
        .writeValueAsString(filtered)
      Files.write(Paths.get(args(3)), newJson.getBytes(StandardCharsets.UTF_8))
    } else {
      println(usage)
      return
    }

    val end = System.currentTimeMillis
    LOGGER.info("Time elapsed " + (end - start) / 1000 + " seconds.")
  }
}
