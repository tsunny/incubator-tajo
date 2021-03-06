/**
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

package tajo.pullserver.retriever;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.http.HttpRequest;
import tajo.pullserver.FileAccessForbiddenException;
import tajo.pullserver.HttpDataServerHandler;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class DirectoryRetriever implements DataRetriever {
  public String baseDir;
  
  public DirectoryRetriever(String baseDir) {
    this.baseDir = baseDir;
  }

  @Override
  public FileChunk [] handle(ChannelHandlerContext ctx, HttpRequest request)
      throws IOException {
    final String path = HttpDataServerHandler.sanitizeUri(request.getUri());
    if (path == null) {
      throw new IllegalArgumentException("Wrong path: " +path);
    }

    File file = new File(baseDir, path);
    if (file.isHidden() || !file.exists()) {
      throw new FileNotFoundException("No such file: " + baseDir + "/" + path);
    }
    if (!file.isFile()) {
      throw new FileAccessForbiddenException("No such file: "
          + baseDir + "/" + path); 
    }
    
    return new FileChunk[] {new FileChunk(file, 0, file.length())};
  }
}
