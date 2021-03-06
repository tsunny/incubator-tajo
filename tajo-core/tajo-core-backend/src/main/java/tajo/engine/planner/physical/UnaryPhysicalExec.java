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

package tajo.engine.planner.physical;

import com.google.common.annotations.VisibleForTesting;
import tajo.TaskAttemptContext;
import tajo.catalog.Schema;

import java.io.IOException;

public abstract class UnaryPhysicalExec extends PhysicalExec {
  protected PhysicalExec child;

  public UnaryPhysicalExec(TaskAttemptContext context,
                           Schema inSchema, Schema outSchema,
                           PhysicalExec child) {
    super(context, inSchema, outSchema);
    this.child = child;
  }

  public PhysicalExec getChild() {
    return this.child;
  }

  @VisibleForTesting
  public void setChild(PhysicalExec child){
    this.child = child;
  }

  public void init() throws IOException {
    child.init();
  }

  public void rescan() throws IOException {
    child.rescan();
  }

  public void close() throws IOException {
    child.close();
  }
}
