/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gearpump.transport.netty

import java.io.Closeable
import java.util.concurrent._

import akka.actor.{ActorRef, ActorSystem, Props}
import org.apache.gearpump.transport.netty.Server.ServerPipelineFactory
import org.apache.gearpump.transport.{ActorLookupById, HostPort}
import org.apache.gearpump.util.LogUtil
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import org.slf4j.{Logger, LoggerFactory}
import com.typesafe.config.Config

import scala.collection.JavaConversions._
import scala.language.implicitConversions

object Context {
  private final val LOG: Logger = LogUtil.getLogger(getClass)

  implicit def toCloseable(fun : () => Any)  = new Closeable {
    override def close = fun()
  }
}

class Context(system : ActorSystem, conf: NettyConfig) extends IContext {
import org.apache.gearpump.transport.netty.Context._

  def this(system : ActorSystem, conf : Config) {
    this(system, new NettyConfig(conf))
  }

  private var closeHandler = new ConcurrentLinkedQueue[Closeable]()
  val maxWorkers: Int = 1

  private lazy val clientChannelFactory: NioClientSocketChannelFactory = {
    val bossFactory: ThreadFactory = new NettyRenameThreadFactory("client" + "-boss")
    val workerFactory: ThreadFactory = new NettyRenameThreadFactory("client" + "-worker")
    val channelFactory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(bossFactory), Executors.newCachedThreadPool(workerFactory), maxWorkers)
    closeHandler.add { ()=>

      LOG.info("Closing all client resources....")
      channelFactory.releaseExternalResources
    }
    channelFactory
  }


  def bind(name: String, lookupActor : ActorLookupById): Int = {
    val taskDispatcher = system.settings.config.getString("gearpump.task-dispatcher")
    val server = system.actorOf(Props(classOf[Server], name, conf, lookupActor).withDispatcher(taskDispatcher), name)
    val (port, channel) = NettyUtil.newNettyServer(name, new ServerPipelineFactory(server), 5242880)
    val factory = channel.getFactory
    closeHandler.add{ () =>
        system.stop(server)
        channel.close()

        LOG.info("Closing all server resources....")
        factory.releaseExternalResources
      }
    port
  }

  def connect(hostPort : HostPort) : ActorRef = {
    val nettyDispatcher = system.settings.config.getString("gearpump.netty-dispatcher")
    val client = system.actorOf(Props(classOf[Client], conf, clientChannelFactory, hostPort).withDispatcher(nettyDispatcher))
    closeHandler.add { () =>

      LOG.info("closing Client actor....")
      system.stop(client)
    }
    client
  }

  /**
   * terminate this context
   */
  def term {

    LOG.info(s"Context.term, cleanup resources...., we have ${closeHandler.size()} items to close...")

    // clean up resource in reverse order so that client actor can be cleaned
    // before clientChannelFactory
    closeHandler.iterator().toArray.reverse.foreach(_.close())
  }
}

