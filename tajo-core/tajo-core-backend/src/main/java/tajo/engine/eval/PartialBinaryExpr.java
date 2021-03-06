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

package tajo.engine.eval;

import tajo.catalog.Schema;
import tajo.catalog.proto.CatalogProtos.DataType;
import tajo.datum.Datum;
import tajo.storage.Tuple;

public class PartialBinaryExpr extends EvalNode {
  
  public PartialBinaryExpr(Type type) {
    super(type);
  }

  public PartialBinaryExpr(Type type, EvalNode left, EvalNode right) {
    super(type, left, right);
  }

  @Override
  public EvalContext newContext() {
    return null;
  }

  @Override
  public DataType [] getValueType() {
    return null;
  }

  @Override
  public String getName() {
    return "nonamed";
  }

  @Override
  public void eval(EvalContext ctx, Schema schema, Tuple tuple) {
    throw new InvalidEvalException(
        "ERROR: the partial binary expression cannot be evluated: "
            + this.toString() );
  }

  @Override
  public Datum terminate(EvalContext ctx) {
    throw new InvalidEvalException(
        "ERROR: the partial binary expression cannot be terminated: "
            + this.toString() );
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof PartialBinaryExpr) {
      PartialBinaryExpr other = (PartialBinaryExpr) obj;
      return type.equals(other.type) &&
          leftExpr.equals(other.leftExpr) &&
          rightExpr.equals(other.rightExpr);
    }
    return false;
  }

  public String toString() {
    return 
        (leftExpr != null ? leftExpr.toString() : "[EMPTY]") 
        + " " + type + " " 
        + (rightExpr != null ? rightExpr.toString() : "[EMPTY]");
  }
}
