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
package org.apache.beam.sdk.io.xml;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.ValidationEventHandler;
import org.apache.beam.sdk.io.BoundedSource;
import org.apache.beam.sdk.io.CompressedSource;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.FileIO;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.io.OffsetBasedSource;
import org.apache.beam.sdk.io.ReadAllViaFileBasedSource;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.display.HasDisplayData;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.apache.beam.vendor.guava.v26_0_jre.com.google.common.annotations.VisibleForTesting;

/** Transforms for reading and writing XML files using JAXB mappers. */
public class XmlIO {
  // CHECKSTYLE.OFF: JavadocStyle
  /**
   * Reads XML files as a {@link PCollection} of a given type mapped via JAXB.
   *
   * <p>The XML files must be of the following form, where {@code root} and {@code record} are XML
   * element names that are defined by the user:
   *
   * <pre>{@code
   * <root>
   * <record> ... </record>
   * <record> ... </record>
   * <record> ... </record>
   * ...
   * <record> ... </record>
   * </root>
   * }</pre>
   *
   * <p>Basically, the XML document should contain a single root element with an inner list
   * consisting entirely of record elements. The records may contain arbitrary XML content; however,
   * that content <b>must not</b> contain the start {@code <record>} or end {@code </record>} tags.
   * This restriction enables reading from large XML files in parallel from different offsets in the
   * file.
   *
   * <p>Root and/or record elements may additionally contain an arbitrary number of XML attributes.
   * Additionally users must provide a class of a JAXB annotated Java type that can be used convert
   * records into Java objects and vice versa using JAXB marshalling/unmarshalling mechanisms.
   * Reading the source will generate a {@code PCollection} of the given JAXB annotated Java type.
   * Optionally users may provide a minimum size of a bundle that should be created for the source.
   *
   * <p>Example:
   *
   * <pre>{@code
   * PCollection<Record> output = p.apply(XmlIO.<Record>read()
   *     .from(file.toPath().toString())
   *     .withRootElement("root")
   *     .withRecordElement("record")
   *     .withRecordClass(Record.class));
   * }</pre>
   *
   * <p>By default, UTF-8 charset is used. To specify a different charset, use {@link
   * Read#withCharset}.
   *
   * <p>Currently, only XML files that use single-byte characters are supported. Using a file that
   * contains multi-byte characters may result in data loss or duplication.
   *
   * @param <T> Type of the objects that represent the records of the XML file. The {@code
   *     PCollection} generated by this source will be of this type.
   */
  // CHECKSTYLE.ON: JavadocStyle
  public static <T> Read<T> read() {
    return new AutoValue_XmlIO_Read.Builder<T>()
        .setConfiguration(
            new AutoValue_XmlIO_MappingConfiguration.Builder<T>()
                .setCharset(StandardCharsets.UTF_8.name())
                .build())
        .setMinBundleSize(1L)
        .setCompression(Compression.AUTO)
        .build();
  }

  /**
   * Like {@link #read}, but reads each file in a {@link PCollection} of {@link ReadableFile}, which
   * allows more flexible usage via different configuration options of {@link FileIO#match} and
   * {@link FileIO#readMatches} that are not explicitly provided for {@link #read}.
   *
   * <p>For example:
   *
   * <pre>{@code
   * PCollection<ReadableFile> files = p
   *     .apply(FileIO.match().filepattern(options.getInputFilepatternProvider()).continuously(
   *       Duration.standardSeconds(30), afterTimeSinceNewOutput(Duration.standardMinutes(5))))
   *     .apply(FileIO.readMatches().withCompression(GZIP));
   *
   * PCollection<Record> output = files.apply(XmlIO.<Record>readFiles()
   *     .withRootElement("root")
   *     .withRecordElement("record")
   *     .withRecordClass(Record.class));
   * }</pre>
   */
  public static <T> ReadFiles<T> readFiles() {
    return new AutoValue_XmlIO_ReadFiles.Builder<T>()
        .setConfiguration(
            new AutoValue_XmlIO_MappingConfiguration.Builder<T>()
                .setCharset(StandardCharsets.UTF_8.name())
                .build())
        .build();
  }

  /**
   * Writes all elements in the input {@link PCollection} to a single XML file using {@link #sink}.
   *
   * <p>For more configurable usage, use {@link #sink} directly with {@link FileIO#write} or {@link
   * FileIO#writeDynamic}.
   */
  public static <T> Write<T> write() {
    return new AutoValue_XmlIO_Write.Builder<T>().setCharset(StandardCharsets.UTF_8.name()).build();
  }

  @AutoValue
  abstract static class MappingConfiguration<T> implements HasDisplayData, Serializable {
    @Nullable
    abstract String getRootElement();

    @Nullable
    abstract String getRecordElement();

    @Nullable
    abstract Class<T> getRecordClass();

    @Nullable
    abstract String getCharset();

    @Nullable
    abstract ValidationEventHandler getValidationEventHandler();

    abstract Builder<T> toBuilder();

    @AutoValue.Builder
    abstract static class Builder<T> {
      abstract Builder<T> setRootElement(String rootElement);

      abstract Builder<T> setRecordElement(String recordElement);

      abstract Builder<T> setRecordClass(Class<T> recordClass);

      abstract Builder<T> setCharset(String charset);

      abstract Builder<T> setValidationEventHandler(ValidationEventHandler validationEventHandler);

      abstract MappingConfiguration<T> build();
    }

    private MappingConfiguration<T> withRootElement(String rootElement) {
      return toBuilder().setRootElement(rootElement).build();
    }

    private MappingConfiguration<T> withRecordElement(String recordElement) {
      return toBuilder().setRecordElement(recordElement).build();
    }

    private MappingConfiguration<T> withRecordClass(Class<T> recordClass) {
      return toBuilder().setRecordClass(recordClass).build();
    }

    private MappingConfiguration<T> withCharset(Charset charset) {
      return toBuilder().setCharset(charset.name()).build();
    }

    private MappingConfiguration<T> withValidationEventHandler(
        ValidationEventHandler validationEventHandler) {
      return toBuilder().setValidationEventHandler(validationEventHandler).build();
    }

    private void validate() {
      checkArgument(getRootElement() != null, "withRootElement() is required");
      checkArgument(getRecordElement() != null, "withRecordElement() is required");
      checkArgument(getRecordClass() != null, "withRecordClass() is required");
      checkArgument(getCharset() != null, "withCharset() is required");
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder
          .addIfNotNull(
              DisplayData.item("rootElement", getRootElement()).withLabel("XML Root Element"))
          .addIfNotNull(
              DisplayData.item("recordElement", getRecordElement()).withLabel("XML Record Element"))
          .addIfNotNull(
              DisplayData.item("recordClass", getRecordClass()).withLabel("XML Record Class"))
          .addIfNotNull(DisplayData.item("charset", getCharset()).withLabel("Charset"));
    }
  }

  /** Implementation of {@link #read}. */
  @AutoValue
  public abstract static class Read<T> extends PTransform<PBegin, PCollection<T>> {
    abstract MappingConfiguration<T> getConfiguration();

    @Nullable
    abstract ValueProvider<String> getFileOrPatternSpec();

    abstract Compression getCompression();

    abstract long getMinBundleSize();

    abstract Builder<T> toBuilder();

    @AutoValue.Builder
    abstract static class Builder<T> {
      abstract Builder<T> setConfiguration(MappingConfiguration<T> configuration);

      abstract Builder<T> setFileOrPatternSpec(ValueProvider<String> fileOrPatternSpec);

      abstract Builder<T> setCompression(Compression compression);

      abstract Builder<T> setMinBundleSize(long minBundleSize);

      abstract Read<T> build();
    }

    /** @deprecated Use {@link Compression} instead. */
    @Deprecated
    public enum CompressionType {
      /** @see Compression#AUTO */
      AUTO(Compression.AUTO),

      /** @see Compression#UNCOMPRESSED */
      UNCOMPRESSED(Compression.UNCOMPRESSED),

      /** @see Compression#GZIP */
      GZIP(Compression.GZIP),

      /** @see Compression#BZIP2 */
      BZIP2(Compression.BZIP2),

      /** @see Compression#ZIP */
      ZIP(Compression.ZIP),

      /** @see Compression#DEFLATE */
      DEFLATE(Compression.DEFLATE);

      private final Compression canonical;

      CompressionType(Compression canonical) {
        this.canonical = canonical;
      }

      /** @see Compression#matches */
      public boolean matches(String filename) {
        return canonical.matches(filename);
      }
    }

    /**
     * Reads a single XML file or a set of XML files defined by a Java "glob" file pattern. Each XML
     * file should be of the form defined in {@link #read}.
     */
    public Read<T> from(String fileOrPatternSpec) {
      return from(StaticValueProvider.of(fileOrPatternSpec));
    }

    /**
     * Reads a single XML file or a set of XML files defined by a Java "glob" file pattern. Each XML
     * file should be of the form defined in {@link #read}. Using ValueProviders.
     */
    public Read<T> from(ValueProvider<String> fileOrPatternSpec) {
      return toBuilder().setFileOrPatternSpec(fileOrPatternSpec).build();
    }

    private Read<T> withConfiguration(MappingConfiguration<T> configuration) {
      return toBuilder().setConfiguration(configuration).build();
    }

    /**
     * Sets name of the root element of the XML document. This will be used to create a valid
     * starting root element when initiating a bundle of records created from an XML document. This
     * is a required parameter.
     */
    public Read<T> withRootElement(String rootElement) {
      return withConfiguration(getConfiguration().withRootElement(rootElement));
    }

    /**
     * Sets name of the record element of the XML document. This will be used to determine offset of
     * the first record of a bundle created from the XML document. This is a required parameter.
     */
    public Read<T> withRecordElement(String recordElement) {
      return withConfiguration(getConfiguration().withRecordElement(recordElement));
    }

    /**
     * Sets a JAXB annotated class that can be populated using a record of the provided XML file.
     * This will be used when unmarshalling record objects from the XML file. This is a required
     * parameter.
     */
    public Read<T> withRecordClass(Class<T> recordClass) {
      return withConfiguration(getConfiguration().withRecordClass(recordClass));
    }

    /**
     * Sets a parameter {@code minBundleSize} for the minimum bundle size of the source. Please
     * refer to {@link OffsetBasedSource} for the definition of minBundleSize. This is an optional
     * parameter.
     */
    public Read<T> withMinBundleSize(long minBundleSize) {
      return toBuilder().setMinBundleSize(minBundleSize).build();
    }

    /** @deprecated use {@link #withCompression}. */
    @Deprecated
    public Read<T> withCompressionType(CompressionType compressionType) {
      return withCompression(compressionType.canonical);
    }

    /** Decompresses all input files using the specified compression type. */
    public Read<T> withCompression(Compression compression) {
      return toBuilder().setCompression(compression).build();
    }

    /** Sets the XML file charset. */
    public Read<T> withCharset(Charset charset) {
      return withConfiguration(getConfiguration().withCharset(charset));
    }

    /**
     * Sets the {@link ValidationEventHandler} to use with JAXB. Calling this with a {@code null}
     * parameter will cause the JAXB unmarshaller event handler to be unspecified.
     */
    public Read<T> withValidationEventHandler(ValidationEventHandler validationEventHandler) {
      return withConfiguration(
          getConfiguration().withValidationEventHandler(validationEventHandler));
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder
          .addIfNotDefault(
              DisplayData.item("minBundleSize", getMinBundleSize())
                  .withLabel("Minimum Bundle Size"),
              1L)
          .add(DisplayData.item("filePattern", getFileOrPatternSpec()).withLabel("File Pattern"))
          .include("configuration", getConfiguration());
    }

    @VisibleForTesting
    BoundedSource<T> createSource() {
      return CompressedSource.from(new XmlSource<>(getFileOrPatternSpec(), getConfiguration(), 1L))
          .withCompression(getCompression());
    }

    @Override
    public PCollection<T> expand(PBegin input) {
      getConfiguration().validate();
      return input.apply(org.apache.beam.sdk.io.Read.from(createSource()));
    }
  }

  /** Implementation of {@link #readFiles}. */
  @AutoValue
  public abstract static class ReadFiles<T>
      extends PTransform<PCollection<ReadableFile>, PCollection<T>> {
    abstract MappingConfiguration<T> getConfiguration();

    abstract Builder<T> toBuilder();

    @AutoValue.Builder
    abstract static class Builder<T> {
      abstract Builder<T> setConfiguration(MappingConfiguration<T> configuration);

      abstract ReadFiles<T> build();
    }

    private ReadFiles<T> withConfiguration(MappingConfiguration<T> configuration) {
      return toBuilder().setConfiguration(configuration).build();
    }

    /** Like {@link Read#withRootElement}. */
    public ReadFiles<T> withRootElement(String rootElement) {
      return withConfiguration(getConfiguration().withRootElement(rootElement));
    }

    /** Like {@link Read#withRecordElement}. */
    public ReadFiles<T> withRecordElement(String recordElement) {
      return withConfiguration(getConfiguration().withRecordElement(recordElement));
    }

    /** Like {@link Read#withRecordClass}. */
    public ReadFiles<T> withRecordClass(Class<T> recordClass) {
      return withConfiguration(getConfiguration().withRecordClass(recordClass));
    }

    /** Like {@link Read#withCharset}. */
    public ReadFiles<T> withCharset(Charset charset) {
      return withConfiguration(getConfiguration().withCharset(charset));
    }

    /** Like {@link Read#withValidationEventHandler}. */
    public ReadFiles<T> withValidationEventHandler(ValidationEventHandler validationEventHandler) {
      return withConfiguration(
          getConfiguration().withValidationEventHandler(validationEventHandler));
    }

    @Override
    public PCollection<T> expand(PCollection<ReadableFile> input) {
      return input.apply(
          new ReadAllViaFileBasedSource<>(
              64 * 1024L * 1024L,
              new CreateSourceFn<>(getConfiguration()),
              JAXBCoder.of(getConfiguration().getRecordClass())));
    }
  }

  private static class CreateSourceFn<T> implements SerializableFunction<String, XmlSource<T>> {
    private final MappingConfiguration<T> configuration;

    CreateSourceFn(MappingConfiguration<T> configuration) {
      this.configuration = configuration;
    }

    @Override
    public XmlSource<T> apply(String input) {
      return new XmlSource<>(StaticValueProvider.of(input), configuration, 1L);
    }
  }

  /** Implementation of {@link #write}. */
  @AutoValue
  public abstract static class Write<T> extends PTransform<PCollection<T>, PDone> {
    @Nullable
    abstract String getFilenamePrefix();

    @Nullable
    abstract Class<T> getRecordClass();

    @Nullable
    abstract String getRootElement();

    @Nullable
    abstract String getCharset();

    abstract Builder<T> toBuilder();

    @AutoValue.Builder
    abstract static class Builder<T> {
      abstract Builder<T> setFilenamePrefix(String prefix);

      abstract Builder<T> setRecordClass(Class<T> recordClass);

      abstract Builder<T> setRootElement(String rootElement);

      abstract Builder<T> setCharset(String charset);

      abstract Write<T> build();
    }

    /**
     * Writes to files with the given path prefix.
     *
     * <p>Output files will have the name {@literal {filenamePrefix}-0000i-of-0000n.xml} where n is
     * the number of output bundles.
     */
    public Write<T> to(String filenamePrefix) {
      return toBuilder().setFilenamePrefix(filenamePrefix).build();
    }

    /**
     * Writes objects of the given class mapped to XML elements using JAXB.
     *
     * <p>The specified class must be able to be used to create a JAXB context.
     */
    public Write<T> withRecordClass(Class<T> recordClass) {
      return toBuilder().setRecordClass(recordClass).build();
    }

    /** Sets the enclosing root element for the generated XML files. */
    public Write<T> withRootElement(String rootElement) {
      return toBuilder().setRootElement(rootElement).build();
    }

    /** Sets the charset used to write the file. */
    public Write<T> withCharset(Charset charset) {
      return toBuilder().setCharset(charset.name()).build();
    }

    @Override
    public PDone expand(PCollection<T> input) {
      checkArgument(getRecordClass() != null, "withRecordClass() is required");
      checkArgument(getRootElement() != null, "withRootElement() is required");
      checkArgument(getFilenamePrefix() != null, "to() is required");
      checkArgument(getCharset() != null, "withCharset() is required");
      try {
        JAXBContext.newInstance(getRecordClass());
      } catch (JAXBException e) {
        throw new RuntimeException("Error binding classes to a JAXB Context.", e);
      }

      ResourceId prefix =
          FileSystems.matchNewResource(getFilenamePrefix(), false /* isDirectory */);
      input.apply(
          FileIO.<T>write()
              .via(
                  sink(getRecordClass())
                      .withCharset(Charset.forName(getCharset()))
                      .withRootElement(getRootElement()))
              .to(prefix.getCurrentDirectory().toString())
              .withPrefix(prefix.getFilename())
              .withSuffix(".xml")
              .withIgnoreWindowing());
      return PDone.in(input.getPipeline());
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder
          .addIfNotNull(
              DisplayData.item("rootElement", getRootElement()).withLabel("XML Root Element"))
          .addIfNotNull(
              DisplayData.item("recordClass", getRecordClass()).withLabel("XML Record Class"))
          .addIfNotNull(DisplayData.item("charset", getCharset()).withLabel("Charset"));
    }
  }

  // CHECKSTYLE.OFF: JavadocStyle
  /**
   * Outputs records as XML-formatted elements using JAXB.
   *
   * <p>The produced file consists of a single root element containing 1 sub-element per element
   * written to the sink.
   *
   * <p>The given class will be used in the marshalling of records in an input PCollection to their
   * XML representation and must be able to be bound using JAXB annotations.
   *
   * <p>For example, consider the following class with JAXB annotations:
   *
   * <pre>
   *  {@literal @}XmlRootElement(name = "word_count_result")
   *  {@literal @}XmlType(propOrder = {"word", "frequency"})
   *  public class WordFrequency {
   *    public String word;
   *    public long frequency;
   *  }
   * </pre>
   *
   * <p>The following will produce XML output with a root element named "words" from a PCollection
   * of WordFrequency objects:
   *
   * <pre>{@code
   * p.apply(FileIO.<WordFrequency>write()
   *     .via(XmlIO.sink(WordFrequency.class).withRootElement("words"))
   *     .to(prefixAndShardTemplate("...", DEFAULT_UNWINDOWED_SHARD_TEMPLATE + ".xml"));
   * }</pre>
   *
   * <p>The output will look like:
   *
   * <pre>{@code
   * <words>
   *  <word_count_result>
   *    <word>decreased</word>
   *    <frequency>1</frequency>
   *  </word_count_result>
   *  <word_count_result>
   *    <word>War</word>
   *    <frequency>4</frequency>
   *  </word_count_result>
   *  <word_count_result>
   *    <word>empress'</word>
   *    <frequency>14</frequency>
   *  </word_count_result>
   *  <word_count_result>
   *    <word>stoops</word>
   *    <frequency>6</frequency>
   *  </word_count_result>
   *  ...
   * </words>
   * }</pre>
   */
  // CHECKSTYLE.ON: JavadocStyle
  public static <T> Sink<T> sink(Class<T> recordClass) {
    return new AutoValue_XmlIO_Sink.Builder<T>()
        .setRecordClass(recordClass)
        .setCharset(StandardCharsets.UTF_8.name())
        .build();
  }

  /** Implementation of {@link #sink}. */
  @AutoValue
  public abstract static class Sink<T> implements FileIO.Sink<T> {
    abstract Class<T> getRecordClass();

    @Nullable
    abstract String getRootElement();

    abstract String getCharset();

    abstract Builder<T> toBuilder();

    @AutoValue.Builder
    abstract static class Builder<T> {
      abstract Builder<T> setRecordClass(Class<T> clazz);

      abstract Builder<T> setRootElement(String rootElement);

      abstract Builder<T> setCharset(String charset);

      abstract Sink<T> build();
    }

    public Sink<T> withRootElement(String rootElement) {
      return toBuilder().setRootElement(rootElement).build();
    }

    public Sink<T> withCharset(Charset charset) {
      return toBuilder().setCharset(charset.name()).build();
    }

    private transient OutputStream outputStream;
    private transient Marshaller marshaller;

    @Override
    public void open(WritableByteChannel channel) throws IOException {
      checkArgument(getRootElement() != null, ".withRootElement() is required");
      try {
        marshaller = JAXBContext.newInstance(getRecordClass()).createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
        marshaller.setProperty(Marshaller.JAXB_ENCODING, getCharset());
      } catch (JAXBException e) {
        throw new IOException(e);
      }

      this.outputStream = Channels.newOutputStream(channel);
      outputStream.write(("<" + getRootElement() + ">\n").getBytes(Charset.forName(getCharset())));
    }

    @Override
    public void write(T element) throws IOException {
      try {
        this.marshaller.marshal(element, outputStream);
      } catch (JAXBException e) {
        throw new IOException(e);
      }
    }

    @Override
    public void flush() throws IOException {
      outputStream.write(("\n</" + getRootElement() + ">").getBytes(Charset.forName(getCharset())));
    }
  }
}
