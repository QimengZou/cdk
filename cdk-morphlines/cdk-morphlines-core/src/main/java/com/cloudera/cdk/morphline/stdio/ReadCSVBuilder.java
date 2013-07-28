/*
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.cdk.morphline.stdio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.cloudera.cdk.morphline.api.Command;
import com.cloudera.cdk.morphline.api.CommandBuilder;
import com.cloudera.cdk.morphline.api.MorphlineCompilationException;
import com.cloudera.cdk.morphline.api.MorphlineContext;
import com.cloudera.cdk.morphline.api.Record;
import com.cloudera.cdk.morphline.shaded.com.googlecode.jcsv.fastreader.CSVReader;
import com.cloudera.cdk.morphline.shaded.com.googlecode.jcsv.fastreader.CSVStrategy;
import com.cloudera.cdk.morphline.shaded.com.googlecode.jcsv.fastreader.CSVTokenizer;
import com.cloudera.cdk.morphline.shaded.com.googlecode.jcsv.fastreader.CSVTokenizerImpl;
import com.cloudera.cdk.morphline.shaded.com.googlecode.jcsv.fastreader.SimpleCSVTokenizer;
import com.typesafe.config.Config;

/**
 * Command that extracts zero or more records from the input stream of the first attachment, 
 * representing a Comma Separated Values (CSV) file.
 * 
 * For the format see http://www.creativyst.com/Doc/Articles/CSV/CSV01.htm.
 */
public final class ReadCSVBuilder implements CommandBuilder {

  @Override
  public Collection<String> getNames() {
    return Collections.singletonList("readCSV");
  }

  @Override
  public Command build(Config config, Command parent, Command child, MorphlineContext context) {
    return new ReadCSV(config, parent, child, context);
  }
  
  
  ///////////////////////////////////////////////////////////////////////////////
  // Nested classes:
  ///////////////////////////////////////////////////////////////////////////////
  private static final class ReadCSV extends AbstractParser {

    private final char separatorChar;
    private final List<String> columnNames;
    private final Charset charset;
    private final boolean ignoreFirstLine;
    private final boolean trim;
    private final String commentPrefix;
    private final String quoteChar;
    private final boolean ignoreEmptyLines = true;
    private final CSVReader csvReader;      
  
    public ReadCSV(Config config, Command parent, Command child, MorphlineContext context) {
      super(config, parent, child, context);
      String separator = getConfigs().getString(config, "separator", ",");
      if (separator.length() != 1) {
        throw new MorphlineCompilationException("CSV separator must be one character only: " + separator, config);
      }
      this.separatorChar = separator.charAt(0);
      this.columnNames = getConfigs().getStringList(config, "columns");
      this.charset = getConfigs().getCharset(config, "charset", null);
      this.ignoreFirstLine = getConfigs().getBoolean(config, "ignoreFirstLine", false);
      this.trim = getConfigs().getBoolean(config, "trim", true);      
      this.quoteChar = getConfigs().getString(config, "quoteChar", Character.toString('"'));
      if (quoteChar.length() > 1) {
        throw new MorphlineCompilationException(
            "Quote character must not have a length of more than one character: " + quoteChar, config);
      }
      if (quoteChar.equals(String.valueOf(separatorChar))) {
        throw new MorphlineCompilationException(
            "Quote character must not be the same as separator: " + quoteChar, config);
      }
      this.commentPrefix = getConfigs().getString(config, "commentPrefix", "#");
      if (commentPrefix.length() > 1) {
        throw new MorphlineCompilationException(
            "Comment prefix must not have a length of more than one character: " + commentPrefix, config);
      }
      csvReader = createCSVReader();
      validateArguments();
    }
  
    @Override
    protected boolean doProcess(Record inputRecord, InputStream stream) throws IOException {
      Record template = inputRecord.copy();
      removeAttachments(template);
      Charset detectedCharset = detectCharset(inputRecord, charset);  
      BufferedReader reader = new BufferedReader(new InputStreamReader(stream, detectedCharset));
      if (ignoreFirstLine) {
        reader.readLine();
      }      
      List<String> columnValues = new ArrayList();

      while (csvReader.readNext(reader, columnValues)) {
        incrementNumRecords();
        Record outputRecord = template.copy();
        for (int i = 0; i < columnValues.size(); i++) {
          if (i >= columnNames.size()) {
            columnNames.add("column" + i);
          }
          String columnName = columnNames.get(i);
          if (columnName.length() == 0) { // empty column name indicates omit this field on output
            outputRecord.removeAll(columnName);
          } else { 
            outputRecord.replaceValues(columnName, trim(columnValues.get(i)));
          }
        }        
        columnValues.clear();
        
        // pass record to next command in chain:
        if (!getChild().process(outputRecord)) {
          return false;
        }
      }
      return true;
    }

    private String trim(String str) {
      return trim ? str.trim() : str;
    }
  
    // Uses a shaded version of jcsv-1.4.0 (https://code.google.com/p/jcsv/) 
    // to reduce the potential for dependency conflicts.
    // TODO: consider replacing impl with http://github.com/FasterXML/jackson-dataformat-csv
    // or http://supercsv.sourceforge.net/release_notes.html
    private CSVReader createCSVReader() {
      char myQuoteChar = '"';
      if (quoteChar.length() > 0) {
        myQuoteChar = quoteChar.charAt(0);
      }
      CSVStrategy strategy = 
          new CSVStrategy(separatorChar, myQuoteChar, commentPrefix, ignoreFirstLine, ignoreEmptyLines);
      
      CSVTokenizer tokenizer = quoteChar.length() == 0 ? new SimpleCSVTokenizer() : new CSVTokenizerImpl();
      return new CSVReader(strategy, tokenizer);
    }
  
  }
}
