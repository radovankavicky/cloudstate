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

package io.cloudstate.proxy.jdbc

import io.cloudstate.proxy.CloudStateProxyMain

object CloudStateJdbcProxyMain {

  def main(args: Array[String]): Unit = {

    val actorSystem = CloudStateProxyMain.start()

    // If in dev mode, we want to ensure the tables get created, which they won't be unless the health check is
    // instantiated
    val config = new CloudStateProxyMain.Configuration(actorSystem.settings.config.getConfig("cloudstate.proxy"))
    if (config.devMode) {
      new SlickEnsureTablesExistReadyCheck(actorSystem)
    }
  }

}
