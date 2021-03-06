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

package tajo.engine.function.builtin;

import tajo.catalog.Column;
import tajo.catalog.function.AggFunction;
import tajo.catalog.function.FunctionContext;
import tajo.catalog.proto.CatalogProtos;
import tajo.catalog.proto.CatalogProtos.DataType;
import tajo.datum.Datum;
import tajo.datum.DatumFactory;
import tajo.datum.StringDatum;
import tajo.storage.Tuple;

public class MinString extends AggFunction<Datum> {

  public MinString() {
    super(new Column[] {
        new Column("val", CatalogProtos.DataType.STRING)
    });
  }

  @Override
  public FunctionContext newContext() {
    return new MinContext();
  }

  @Override
  public void eval(FunctionContext ctx, Tuple params) {
    MinContext minCtx = (MinContext) ctx;
    if (minCtx.min == null) {
      minCtx.min = params.get(0).asChars();
    } else if (params.get(0).asChars().compareTo(minCtx.min) < 0) {
      minCtx.min = params.get(0).asChars();
    }
  }

  @Override
  public Datum getPartialResult(FunctionContext ctx) {
    return DatumFactory.createString(((MinContext)ctx).min);
  }

  @Override
  public DataType[] getPartialResultType() {
    return new DataType[] {DataType.STRING};
  }

  @Override
  public StringDatum terminate(FunctionContext ctx) {
    return DatumFactory.createString(((MinContext)ctx).min);
  }

  private class MinContext implements FunctionContext {
    String min;
  }
}
