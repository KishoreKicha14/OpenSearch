/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.common.lucene;

import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.document.LatLonDocValuesField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FilterCodecReader;
import org.apache.lucene.index.FilterDirectoryReader;
import org.apache.lucene.index.FilterLeafReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.SortedSetSortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.grouping.CollapseTopFieldDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.Lock;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.opensearch.ExceptionsHelper;
import org.opensearch.common.Nullable;
import org.opensearch.common.SuppressForbidden;
import org.opensearch.common.lucene.search.TopDocsAndMaxScore;
import org.opensearch.common.util.iterable.Iterables;
import org.opensearch.core.common.Strings;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.index.analysis.AnalyzerScope;
import org.opensearch.index.analysis.NamedAnalyzer;
import org.opensearch.index.fielddata.IndexFieldData;
import org.opensearch.search.sort.SortedWiderNumericSortField;

import java.io.IOException;
import java.math.BigInteger;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Main lucene class.
 *
 * @opensearch.internal
 */
public class Lucene {
    public static final String LATEST_CODEC = "Lucene101";

    public static final String SOFT_DELETES_FIELD = "__soft_deletes";
    public static final String PARENT_FIELD = "__nested_parent";

    public static final NamedAnalyzer STANDARD_ANALYZER = new NamedAnalyzer("_standard", AnalyzerScope.GLOBAL, new StandardAnalyzer());
    public static final NamedAnalyzer KEYWORD_ANALYZER = new NamedAnalyzer("_keyword", AnalyzerScope.GLOBAL, new KeywordAnalyzer());
    public static final NamedAnalyzer WHITESPACE_ANALYZER = new NamedAnalyzer(
        "_whitespace",
        AnalyzerScope.GLOBAL,
        new WhitespaceAnalyzer()
    );

    public static final ScoreDoc[] EMPTY_SCORE_DOCS = new ScoreDoc[0];

    public static final TopDocs EMPTY_TOP_DOCS = new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), EMPTY_SCORE_DOCS);

    private Lucene() {}

    /**
     * Reads the segments infos, failing if it fails to load
     */
    public static SegmentInfos readSegmentInfos(Directory directory) throws IOException {
        return SegmentInfos.readLatestCommit(directory);
    }

    /**
     * A variant of {@link #readSegmentInfos(Directory)} that supports reading indices written by
     * older major versions of Lucene. This leverages Lucene's "expert" readLatestCommit API. The
     * {@link org.opensearch.Version} parameter determines the minimum supported Lucene major version.
     */
    public static SegmentInfos readSegmentInfos(Directory directory, org.opensearch.Version minimumVersion) throws IOException {
        final int minSupportedLuceneMajor = minimumVersion.minimumIndexCompatibilityVersion().luceneVersion.major;
        return SegmentInfos.readLatestCommit(directory, minSupportedLuceneMajor);
    }

    /**
     * Returns an iterable that allows to iterate over all files in this segments info
     */
    public static Iterable<String> files(SegmentInfos infos) throws IOException {
        final List<Collection<String>> list = new ArrayList<>();
        list.add(Collections.singleton(infos.getSegmentsFileName()));
        for (SegmentCommitInfo info : infos) {
            list.add(info.files());
        }
        return Iterables.flatten(list);
    }

    /**
     * Returns the number of documents in the index referenced by this {@link SegmentInfos}
     */
    public static int getNumDocs(SegmentInfos info) {
        int numDocs = 0;
        for (SegmentCommitInfo si : info) {
            numDocs += si.info.maxDoc() - si.getDelCount() - si.getSoftDelCount();
        }
        return numDocs;
    }

    /**
     * Reads the segments infos from the given commit, failing if it fails to load
     */
    public static SegmentInfos readSegmentInfos(IndexCommit commit) throws IOException {
        // Using commit.getSegmentsFileName() does NOT work here, have to
        // manually create the segment filename
        String filename = IndexFileNames.fileNameFromGeneration(IndexFileNames.SEGMENTS, "", commit.getGeneration());
        return SegmentInfos.readCommit(commit.getDirectory(), filename);
    }

    /**
     * Reads the segments infos from the given segments file name, failing if it fails to load
     */
    private static SegmentInfos readSegmentInfos(String segmentsFileName, Directory directory) throws IOException {
        return SegmentInfos.readCommit(directory, segmentsFileName);
    }

    /**
     * This method removes all files from the given directory that are not referenced by the given segments file.
     * This method will open an IndexWriter and relies on index file deleter to remove all unreferenced files. Segment files
     * that are newer than the given segments file are removed forcefully to prevent problems with IndexWriter opening a potentially
     * broken commit point / leftover.
     * <b>Note:</b> this method will fail if there is another IndexWriter open on the given directory. This method will also acquire
     * a write lock from the directory while pruning unused files. This method expects an existing index in the given directory that has
     * the given segments file.
     */
    public static SegmentInfos pruneUnreferencedFiles(String segmentsFileName, Directory directory) throws IOException {
        final SegmentInfos si = readSegmentInfos(segmentsFileName, directory);
        try (Lock writeLock = directory.obtainLock(IndexWriter.WRITE_LOCK_NAME)) {
            int foundSegmentFiles = 0;
            for (final String file : directory.listAll()) {
                /*
                 * we could also use a deletion policy here but in the case of snapshot and restore
                 * sometimes we restore an index and override files that were referenced by a "future"
                 * commit. If such a commit is opened by the IW it would likely throw a corrupted index exception
                 * since checksums don's match anymore. that's why we prune the name here directly.
                 * We also want the caller to know if we were not able to remove a segments_N file.
                 */
                if (file.startsWith(IndexFileNames.SEGMENTS)) {
                    foundSegmentFiles++;
                    if (file.equals(si.getSegmentsFileName()) == false) {
                        directory.deleteFile(file); // remove all segment_N files except of the one we wanna keep
                    }
                }
            }
            assert SegmentInfos.getLastCommitSegmentsFileName(directory).equals(segmentsFileName);
            if (foundSegmentFiles == 0) {
                throw new IllegalStateException("no commit found in the directory");
            }
        }
        final IndexCommit cp = getIndexCommit(si, directory);
        try (
            IndexWriter writer = new IndexWriter(
                directory,
                new IndexWriterConfig(Lucene.STANDARD_ANALYZER).setSoftDeletesField(Lucene.SOFT_DELETES_FIELD)
                    .setIndexCommit(cp)
                    .setCommitOnClose(false)
                    .setMergePolicy(NoMergePolicy.INSTANCE)
                    .setOpenMode(IndexWriterConfig.OpenMode.APPEND)
            )
        ) {
            // do nothing and close this will kick off IndexFileDeleter which will remove all pending files
        }
        return si;
    }

    /**
     * Returns an index commit for the given {@link SegmentInfos} in the given directory.
     */
    public static IndexCommit getIndexCommit(SegmentInfos si, Directory directory) throws IOException {
        return new CommitPoint(si, directory);
    }

    /**
     * This method removes all lucene files from the given directory. It will first try to delete all commit points / segments
     * files to ensure broken commits or corrupted indices will not be opened in the future. If any of the segment files can't be deleted
     * this operation fails.
     */
    public static void cleanLuceneIndex(Directory directory) throws IOException {
        try (Lock writeLock = directory.obtainLock(IndexWriter.WRITE_LOCK_NAME)) {
            for (final String file : directory.listAll()) {
                if (file.startsWith(IndexFileNames.SEGMENTS)) {
                    directory.deleteFile(file); // remove all segment_N files
                }
            }
        }
        try (
            IndexWriter writer = new IndexWriter(
                directory,
                new IndexWriterConfig(Lucene.STANDARD_ANALYZER).setSoftDeletesField(Lucene.SOFT_DELETES_FIELD)
                    .setMergePolicy(NoMergePolicy.INSTANCE) // no merges
                    .setCommitOnClose(false) // no commits
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE) // force creation - don't append...
            )
        ) {
            // do nothing and close this will kick of IndexFileDeleter which will remove all pending files
        }
    }

    public static void checkSegmentInfoIntegrity(final Directory directory) throws IOException {
        new SegmentInfos.FindSegmentsFile(directory) {

            @Override
            protected Object doBody(String segmentFileName) throws IOException {
                try (IndexInput input = directory.openInput(segmentFileName, IOContext.READONCE)) {
                    CodecUtil.checksumEntireFile(input);
                }
                return null;
            }
        }.run();
    }

    /**
     * Check whether there is one or more documents matching the provided query.
     */
    public static boolean exists(IndexSearcher searcher, Query query) throws IOException {
        final Weight weight = searcher.createWeight(searcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1f);
        // the scorer API should be more efficient at stopping after the first
        // match than the bulk scorer API
        for (LeafReaderContext context : searcher.getIndexReader().leaves()) {
            final Scorer scorer = weight.scorer(context);
            if (scorer == null) {
                continue;
            }
            final Bits liveDocs = context.reader().getLiveDocs();
            final DocIdSetIterator iterator = scorer.iterator();
            for (int doc = iterator.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = iterator.nextDoc()) {
                if (liveDocs == null || liveDocs.get(doc)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static TotalHits readTotalHits(StreamInput in) throws IOException {
        long totalHits = in.readVLong();
        TotalHits.Relation totalHitsRelation = in.readEnum(TotalHits.Relation.class);
        return new TotalHits(totalHits, totalHitsRelation);
    }

    public static TopDocsAndMaxScore readTopDocs(StreamInput in) throws IOException {
        byte type = in.readByte();
        if (type == 0) {
            TotalHits totalHits = readTotalHits(in);
            float maxScore = in.readFloat();

            final int scoreDocCount = in.readVInt();
            final ScoreDoc[] scoreDocs;
            if (scoreDocCount == 0) {
                scoreDocs = EMPTY_SCORE_DOCS;
            } else {
                scoreDocs = new ScoreDoc[scoreDocCount];
                for (int i = 0; i < scoreDocs.length; i++) {
                    scoreDocs[i] = new ScoreDoc(in.readVInt(), in.readFloat());
                }
            }
            return new TopDocsAndMaxScore(new TopDocs(totalHits, scoreDocs), maxScore);
        } else if (type == 1) {
            TotalHits totalHits = readTotalHits(in);
            float maxScore = in.readFloat();
            SortField[] fields = in.readArray(Lucene::readSortField, SortField[]::new);
            FieldDoc[] fieldDocs = new FieldDoc[in.readVInt()];
            for (int i = 0; i < fieldDocs.length; i++) {
                fieldDocs[i] = readFieldDoc(in);
            }
            return new TopDocsAndMaxScore(new TopFieldDocs(totalHits, fieldDocs, fields), maxScore);
        } else if (type == 2) {
            TotalHits totalHits = readTotalHits(in);
            float maxScore = in.readFloat();

            String field = in.readString();
            SortField[] fields = in.readArray(Lucene::readSortField, SortField[]::new);
            int size = in.readVInt();
            Object[] collapseValues = new Object[size];
            FieldDoc[] fieldDocs = new FieldDoc[size];
            for (int i = 0; i < fieldDocs.length; i++) {
                fieldDocs[i] = readFieldDoc(in);
                collapseValues[i] = readSortValue(in);
            }
            return new TopDocsAndMaxScore(new CollapseTopFieldDocs(field, totalHits, fieldDocs, fields, collapseValues), maxScore);
        } else {
            throw new IllegalStateException("Unknown type " + type);
        }
    }

    public static FieldDoc readFieldDoc(StreamInput in) throws IOException {
        Comparable[] cFields = new Comparable[in.readVInt()];
        for (int j = 0; j < cFields.length; j++) {
            cFields[j] = readTypedValue(in);
        }
        return new FieldDoc(in.readVInt(), in.readFloat(), cFields);
    }

    public static Comparable readSortValue(StreamInput in) throws IOException {
        return readTypedValue(in);
    }

    /**
     * Reads a typed value from the stream based on a type byte prefix.
     *
     * @param in the input stream
     * @return the deserialized Comparable value
     * @throws IOException if reading fails or type is unknown
     */
    private static Comparable readTypedValue(StreamInput in) throws IOException {
        byte type = in.readByte();
        return switch (type) {
            case 0 -> null;
            case 1 -> in.readString();
            case 2 -> in.readInt();
            case 3 -> in.readLong();
            case 4 -> in.readFloat();
            case 5 -> in.readDouble();
            case 6 -> in.readByte();
            case 7 -> in.readShort();
            case 8 -> in.readBoolean();
            case 9 -> in.readBytesRef();
            case 10 -> new BigInteger(in.readString());
            default -> throw new IOException("Can't match type [" + type + "]");
        };
    }

    public static ScoreDoc readScoreDoc(StreamInput in) throws IOException {
        return new ScoreDoc(in.readVInt(), in.readFloat());
    }

    private static final Class<?> GEO_DISTANCE_SORT_TYPE_CLASS = LatLonDocValuesField.newDistanceSort("some_geo_field", 0, 0).getClass();

    public static void writeTotalHits(StreamOutput out, TotalHits totalHits) throws IOException {
        out.writeVLong(totalHits.value());
        out.writeEnum(totalHits.relation());
    }

    public static void writeTopDocs(StreamOutput out, TopDocsAndMaxScore topDocs) throws IOException {
        if (topDocs.topDocs instanceof CollapseTopFieldDocs) {
            out.writeByte((byte) 2);
            CollapseTopFieldDocs collapseDocs = (CollapseTopFieldDocs) topDocs.topDocs;

            writeTotalHits(out, topDocs.topDocs.totalHits);
            out.writeFloat(topDocs.maxScore);

            out.writeString(collapseDocs.field);
            out.writeArray(Lucene::writeSortField, collapseDocs.fields);

            out.writeVInt(topDocs.topDocs.scoreDocs.length);
            for (int i = 0; i < topDocs.topDocs.scoreDocs.length; i++) {
                ScoreDoc doc = collapseDocs.scoreDocs[i];
                writeFieldDoc(out, (FieldDoc) doc);
                writeSortValue(out, collapseDocs.collapseValues[i]);
            }
        } else if (topDocs.topDocs instanceof TopFieldDocs) {
            out.writeByte((byte) 1);
            TopFieldDocs topFieldDocs = (TopFieldDocs) topDocs.topDocs;

            writeTotalHits(out, topDocs.topDocs.totalHits);
            out.writeFloat(topDocs.maxScore);

            out.writeArray(Lucene::writeSortField, topFieldDocs.fields);

            out.writeVInt(topDocs.topDocs.scoreDocs.length);
            for (ScoreDoc doc : topFieldDocs.scoreDocs) {
                writeFieldDoc(out, (FieldDoc) doc);
            }
        } else {
            out.writeByte((byte) 0);
            writeTotalHits(out, topDocs.topDocs.totalHits);
            out.writeFloat(topDocs.maxScore);

            out.writeVInt(topDocs.topDocs.scoreDocs.length);
            for (ScoreDoc doc : topDocs.topDocs.scoreDocs) {
                writeScoreDoc(out, doc);
            }
        }
    }

    private static void writeMissingValue(StreamOutput out, Object missingValue) throws IOException {
        if (missingValue == SortField.STRING_FIRST) {
            out.writeByte((byte) 1);
        } else if (missingValue == SortField.STRING_LAST) {
            out.writeByte((byte) 2);
        } else {
            out.writeByte((byte) 0);
            out.writeGenericValue(missingValue);
        }
    }

    private static Object readMissingValue(StreamInput in) throws IOException {
        final byte id = in.readByte();
        switch (id) {
            case 0:
                return in.readGenericValue();
            case 1:
                return SortField.STRING_FIRST;
            case 2:
                return SortField.STRING_LAST;
            default:
                throw new IOException("Unknown missing value id: " + id);
        }
    }

    public static void writeSortValue(StreamOutput out, Object field) throws IOException {
        if (field == null) {
            out.writeByte((byte) 0);
        } else {
            Class type = field.getClass();
            if (type == String.class) {
                out.writeByte((byte) 1);
                out.writeString((String) field);
            } else if (type == Integer.class) {
                out.writeByte((byte) 2);
                out.writeInt((Integer) field);
            } else if (type == Long.class) {
                out.writeByte((byte) 3);
                out.writeLong((Long) field);
            } else if (type == Float.class) {
                out.writeByte((byte) 4);
                out.writeFloat((Float) field);
            } else if (type == Double.class) {
                out.writeByte((byte) 5);
                out.writeDouble((Double) field);
            } else if (type == Byte.class) {
                out.writeByte((byte) 6);
                out.writeByte((Byte) field);
            } else if (type == Short.class) {
                out.writeByte((byte) 7);
                out.writeShort((Short) field);
            } else if (type == Boolean.class) {
                out.writeByte((byte) 8);
                out.writeBoolean((Boolean) field);
            } else if (type == BytesRef.class) {
                out.writeByte((byte) 9);
                out.writeBytesRef((BytesRef) field);
            } else if (type == BigInteger.class) {
                // TODO: improve serialization of BigInteger
                out.writeByte((byte) 10);
                out.writeString(field.toString());
            } else {
                throw new IOException("Can't handle sort field value of type [" + type + "]");
            }
        }
    }

    public static void writeFieldDoc(StreamOutput out, FieldDoc fieldDoc) throws IOException {
        out.writeVInt(fieldDoc.fields.length);
        for (Object field : fieldDoc.fields) {
            writeSortValue(out, field);
        }
        out.writeVInt(fieldDoc.doc);
        out.writeFloat(fieldDoc.score);
    }

    public static void writeScoreDoc(StreamOutput out, ScoreDoc scoreDoc) throws IOException {
        if (!scoreDoc.getClass().equals(ScoreDoc.class)) {
            throw new IllegalArgumentException("This method can only be used to serialize a ScoreDoc, not a " + scoreDoc.getClass());
        }
        out.writeVInt(scoreDoc.doc);
        out.writeFloat(scoreDoc.score);
    }

    // LUCENE 4 UPGRADE: We might want to maintain our own ordinal, instead of Lucene's ordinal
    public static SortField.Type readSortType(StreamInput in) throws IOException {
        return SortField.Type.values()[in.readVInt()];
    }

    public static SortField readSortField(StreamInput in) throws IOException {
        String field = null;
        if (in.readBoolean()) {
            field = in.readString();
        }
        SortField.Type sortType = readSortType(in);
        Object missingValue = readMissingValue(in);
        boolean reverse = in.readBoolean();
        SortField sortField = new SortField(field, sortType, reverse);
        if (missingValue != null) {
            sortField.setMissingValue(missingValue);
        }
        return sortField;
    }

    public static void writeSortType(StreamOutput out, SortField.Type sortType) throws IOException {
        out.writeVInt(sortType.ordinal());
    }

    public static void writeSortField(StreamOutput out, SortField sortField) throws IOException {
        if (sortField.getClass() == GEO_DISTANCE_SORT_TYPE_CLASS) {
            // for geo sorting, we replace the SortField with a SortField that assumes a double field.
            // this works since the SortField is only used for merging top docs
            SortField newSortField = new SortField(sortField.getField(), SortField.Type.DOUBLE);
            newSortField.setMissingValue(sortField.getMissingValue());
            sortField = newSortField;
        } else if (sortField.getClass() == SortedSetSortField.class) {
            // for multi-valued sort field, we replace the SortedSetSortField with a simple SortField.
            // It works because the sort field is only used to merge results from different shards.
            SortField newSortField = new SortField(sortField.getField(), SortField.Type.STRING, sortField.getReverse());
            newSortField.setMissingValue(sortField.getMissingValue());
            sortField = newSortField;
        } else if (sortField.getClass() == SortedNumericSortField.class || sortField.getClass() == SortedWiderNumericSortField.class) {
            // for multi-valued sort field, we replace the SortedNumericSortField/SortedWiderNumericSortField with a simple SortField.
            // It works because the sort field is only used to merge results from different shards.
            SortField newSortField = new SortField(
                sortField.getField(),
                ((SortedNumericSortField) sortField).getNumericType(),
                sortField.getReverse()
            );
            newSortField.setMissingValue(sortField.getMissingValue());
            sortField = newSortField;
        }

        if (sortField.getClass() != SortField.class) {
            throw new IllegalArgumentException("Cannot serialize SortField impl [" + sortField + "]");
        }
        if (sortField.getField() == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeString(sortField.getField());
        }
        if (sortField.getComparatorSource() != null) {
            IndexFieldData.XFieldComparatorSource comparatorSource = (IndexFieldData.XFieldComparatorSource) sortField
                .getComparatorSource();
            writeSortType(out, comparatorSource.reducedType());
            writeMissingValue(out, comparatorSource.missingValue(sortField.getReverse()));
        } else {
            writeSortType(out, sortField.getType());
            writeMissingValue(out, sortField.getMissingValue());
        }
        out.writeBoolean(sortField.getReverse());
    }

    private static Number readExplanationValue(StreamInput in) throws IOException {
        final int numberType = in.readByte();
        switch (numberType) {
            case 0:
                return in.readFloat();
            case 1:
                return in.readDouble();
            case 2:
                return in.readZLong();
            default:
                throw new IOException("Unexpected number type: " + numberType);
        }
    }

    public static Explanation readExplanation(StreamInput in) throws IOException {
        boolean match = in.readBoolean();
        String description = in.readString();
        final Explanation[] subExplanations = new Explanation[in.readVInt()];
        for (int i = 0; i < subExplanations.length; ++i) {
            subExplanations[i] = readExplanation(in);
        }
        if (match) {
            return Explanation.match(readExplanationValue(in), description, subExplanations);
        } else {
            return Explanation.noMatch(description, subExplanations);
        }
    }

    private static void writeExplanationValue(StreamOutput out, Number value) throws IOException {
        if (value instanceof Float) {
            out.writeByte((byte) 0);
            out.writeFloat(value.floatValue());
        } else if (value instanceof Double) {
            out.writeByte((byte) 1);
            out.writeDouble(value.doubleValue());
        } else {
            out.writeByte((byte) 2);
            out.writeZLong(value.longValue());
        }
    }

    public static void writeExplanation(StreamOutput out, Explanation explanation) throws IOException {
        out.writeBoolean(explanation.isMatch());
        out.writeString(explanation.getDescription());
        Explanation[] subExplanations = explanation.getDetails();
        out.writeVInt(subExplanations.length);
        for (Explanation subExp : subExplanations) {
            writeExplanation(out, subExp);
        }
        if (explanation.isMatch()) {
            writeExplanationValue(out, explanation.getValue());
        }
    }

    public static boolean indexExists(final Directory directory) throws IOException {
        return DirectoryReader.indexExists(directory);
    }

    /**
     * Returns {@code true} iff the given exception or
     * one of it's causes is an instance of {@link CorruptIndexException},
     * {@link IndexFormatTooOldException}, or {@link IndexFormatTooNewException} otherwise {@code false}.
     */
    public static boolean isCorruptionException(Throwable t) {
        return ExceptionsHelper.unwrapCorruption(t) != null;
    }

    /**
     * Parses the version string lenient and returns the default value if the given string is null or empty
     */
    public static Version parseVersionLenient(String toParse, Version defaultValue) {
        return LenientParser.parse(toParse, defaultValue);
    }

    /**
     * Tries to extract a segment reader from the given index reader.
     * If no SegmentReader can be extracted an {@link IllegalStateException} is thrown.
     */
    public static SegmentReader segmentReader(LeafReader reader) {
        if (reader instanceof SegmentReader) {
            return (SegmentReader) reader;
        } else if (reader instanceof FilterLeafReader) {
            final FilterLeafReader fReader = (FilterLeafReader) reader;
            return segmentReader(FilterLeafReader.unwrap(fReader));
        } else if (reader instanceof FilterCodecReader) {
            final FilterCodecReader fReader = (FilterCodecReader) reader;
            return segmentReader(FilterCodecReader.unwrap(fReader));
        }
        // hard fail - we can't get a SegmentReader
        throw new IllegalStateException("Can not extract segment reader from given index reader [" + reader + "]");
    }

    @SuppressForbidden(reason = "Version#parseLeniently() used in a central place")
    private static final class LenientParser {
        public static Version parse(String toParse, Version defaultValue) {
            if (Strings.hasLength(toParse)) {
                try {
                    return Version.parseLeniently(toParse);
                } catch (ParseException e) {
                    // pass to default
                }
            }
            return defaultValue;
        }
    }

    private static final class CommitPoint extends IndexCommit {
        private String segmentsFileName;
        private final Collection<String> files;
        private final Directory dir;
        private final long generation;
        private final Map<String, String> userData;
        private final int segmentCount;

        private CommitPoint(SegmentInfos infos, Directory dir) throws IOException {
            segmentsFileName = infos.getSegmentsFileName();
            this.dir = dir;
            userData = infos.getUserData();
            files = Collections.unmodifiableCollection(infos.files(true));
            generation = infos.getGeneration();
            segmentCount = infos.size();
        }

        @Override
        public String toString() {
            return "DirectoryReader.ReaderCommit(" + segmentsFileName + ")";
        }

        @Override
        public int getSegmentCount() {
            return segmentCount;
        }

        @Override
        public String getSegmentsFileName() {
            return segmentsFileName;
        }

        @Override
        public Collection<String> getFileNames() {
            return files;
        }

        @Override
        public Directory getDirectory() {
            return dir;
        }

        @Override
        public long getGeneration() {
            return generation;
        }

        @Override
        public boolean isDeleted() {
            return false;
        }

        @Override
        public Map<String, String> getUserData() {
            return userData;
        }

        @Override
        public void delete() {
            throw new UnsupportedOperationException("This IndexCommit does not support deletions");
        }
    }

    /**
     * Return a {@link Bits} view of the provided scorer.
     * <b>NOTE</b>: that the returned {@link Bits} instance MUST be consumed in order.
     * @see #asSequentialAccessBits(int, ScorerSupplier, long)
     */
    public static Bits asSequentialAccessBits(final int maxDoc, @Nullable ScorerSupplier scorerSupplier) throws IOException {
        return asSequentialAccessBits(maxDoc, scorerSupplier, 0L);
    }

    /**
     * Given a {@link ScorerSupplier}, return a {@link Bits} instance that will match
     * all documents contained in the set.
     * <b>NOTE</b>: that the returned {@link Bits} instance MUST be consumed in order.
     * @param estimatedGetCount an estimation of the number of times that {@link Bits#get} will get called
     */
    public static Bits asSequentialAccessBits(final int maxDoc, @Nullable ScorerSupplier scorerSupplier, long estimatedGetCount)
        throws IOException {
        if (scorerSupplier == null) {
            return new Bits.MatchNoBits(maxDoc);
        }
        // Since we want bits, we need random-access
        final Scorer scorer = scorerSupplier.get(estimatedGetCount); // this never returns null
        final TwoPhaseIterator twoPhase = scorer.twoPhaseIterator();
        final DocIdSetIterator iterator;
        if (twoPhase == null) {
            iterator = scorer.iterator();
        } else {
            iterator = twoPhase.approximation();
        }

        return new Bits() {

            int previous = -1;
            boolean previousMatched = false;

            @Override
            public boolean get(int index) {
                if (index < 0 || index >= maxDoc) {
                    throw new IndexOutOfBoundsException(index + " is out of bounds: [" + 0 + "-" + maxDoc + "[");
                }
                if (index < previous) {
                    throw new IllegalArgumentException(
                        "This Bits instance can only be consumed in order. "
                            + "Got called on ["
                            + index
                            + "] while previously called on ["
                            + previous
                            + "]"
                    );
                }
                if (index == previous) {
                    // we cache whether it matched because it is illegal to call
                    // twoPhase.matches() twice
                    return previousMatched;
                }
                previous = index;

                int doc = iterator.docID();
                if (doc < index) {
                    try {
                        doc = iterator.advance(index);
                    } catch (IOException e) {
                        throw new IllegalStateException("Cannot advance iterator", e);
                    }
                }
                if (index == doc) {
                    try {
                        return previousMatched = twoPhase == null || twoPhase.matches();
                    } catch (IOException e) {
                        throw new IllegalStateException("Cannot validate match", e);
                    }
                }
                return previousMatched = false;
            }

            @Override
            public int length() {
                return maxDoc;
            }
        };
    }

    /**
     * Whether a query sorted by {@code searchSort} can be early-terminated if the index is sorted by {@code indexSort}.
     */
    public static boolean canEarlyTerminate(Sort searchSort, Sort indexSort) {
        final SortField[] fields1 = searchSort.getSort();
        final SortField[] fields2 = indexSort.getSort();
        // early termination is possible if fields1 is a prefix of fields2
        if (fields1.length > fields2.length) {
            return false;
        }
        return Arrays.asList(fields1).equals(Arrays.asList(fields2).subList(0, fields1.length));
    }

    /**
     * Wraps a directory reader to make all documents live except those were rolled back
     * or hard-deleted due to non-aborting exceptions during indexing.
     * The wrapped reader can be used to query all documents.
     *
     * @param in the input directory reader
     * @return the wrapped reader
     */
    public static DirectoryReader wrapAllDocsLive(DirectoryReader in) throws IOException {
        return new DirectoryReaderWithAllLiveDocs(in);
    }

    private static final class DirectoryReaderWithAllLiveDocs extends FilterDirectoryReader {
        static final class LeafReaderWithLiveDocs extends FilterLeafReader {
            final Bits liveDocs;
            final int numDocs;

            LeafReaderWithLiveDocs(LeafReader in, Bits liveDocs, int numDocs) {
                super(in);
                this.liveDocs = liveDocs;
                this.numDocs = numDocs;
            }

            @Override
            public Bits getLiveDocs() {
                return liveDocs;
            }

            @Override
            public int numDocs() {
                return numDocs;
            }

            @Override
            public CacheHelper getCoreCacheHelper() {
                return in.getCoreCacheHelper();
            }

            @Override
            public CacheHelper getReaderCacheHelper() {
                return null; // Modifying liveDocs
            }
        }

        DirectoryReaderWithAllLiveDocs(DirectoryReader in) throws IOException {
            super(in, new SubReaderWrapper() {
                @Override
                public LeafReader wrap(LeafReader leaf) {
                    final SegmentReader segmentReader = segmentReader(leaf);
                    final Bits hardLiveDocs = segmentReader.getHardLiveDocs();
                    if (hardLiveDocs == null) {
                        return new LeafReaderWithLiveDocs(leaf, null, leaf.maxDoc());
                    }
                    // Once soft-deletes is enabled, we no longer hard-update or hard-delete documents directly.
                    // Two scenarios that we have hard-deletes: (1) from old segments where soft-deletes was disabled,
                    // (2) when IndexWriter hits non-aborted exceptions. These two cases, IW flushes SegmentInfos
                    // before exposing the hard-deletes, thus we can use the hard-delete count of SegmentInfos.
                    final int numDocs = segmentReader.maxDoc() - segmentReader.getSegmentInfo().getDelCount();
                    assert numDocs == popCount(hardLiveDocs) : numDocs + " != " + popCount(hardLiveDocs);
                    return new LeafReaderWithLiveDocs(segmentReader, hardLiveDocs, numDocs);
                }
            });
        }

        @Override
        protected DirectoryReader doWrapDirectoryReader(DirectoryReader in) throws IOException {
            return wrapAllDocsLive(in);
        }

        @Override
        public CacheHelper getReaderCacheHelper() {
            return null; // Modifying liveDocs
        }
    }

    private static int popCount(Bits bits) {
        assert bits != null;
        int onBits = 0;
        for (int i = 0; i < bits.length(); i++) {
            if (bits.get(i)) {
                onBits++;
            }
        }
        return onBits;
    }

    /**
     * Returns a numeric docvalues which can be used to soft-delete documents.
     */
    public static NumericDocValuesField newSoftDeletesField() {
        return new NumericDocValuesField(SOFT_DELETES_FIELD, 1);
    }

}
