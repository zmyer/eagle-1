/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.eagle.app;

import org.apache.eagle.common.config.ConfigOptionParser;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.cli.ParseException;

public interface ApplicationTool {
    /**
     * Run application through CLI.
     *
     * @param args application arguments
     */
    default void run(String[] args) {
        try {
            run(new ConfigOptionParser().load(args));
        } catch (ParseException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    void run(Config config);

    default void run() {
        run(ConfigFactory.load());
    }
}