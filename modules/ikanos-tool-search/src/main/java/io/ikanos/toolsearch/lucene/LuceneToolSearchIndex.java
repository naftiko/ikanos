/**
 * Copyright 2025-2026 Naftiko
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.ikanos.toolsearch.lucene;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.FlattenGraphFilterFactory;
import org.apache.lucene.analysis.core.LowerCaseFilterFactory;
import org.apache.lucene.analysis.custom.CustomAnalyzer;
import org.apache.lucene.analysis.en.EnglishPossessiveFilterFactory;
import org.apache.lucene.analysis.en.PorterStemFilterFactory;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilterFactory;
import org.apache.lucene.analysis.standard.StandardTokenizerFactory;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.util.BytesRef;
import io.ikanos.engine.exposes.mcp.search.ToolSearchDocument;
import io.ikanos.engine.exposes.mcp.search.ToolSearchIndex;
import io.ikanos.engine.exposes.mcp.search.ToolSearchQuery;

/**
 * An in-memory BM25 index over the tools of one MCP endpoint.
 *
 * <p>The index is written once, in the constructor, and is immutable afterwards, so one
 * {@link IndexSearcher} serves concurrent searches.</p>
 *
 * <p>The query text is analysed with the same analyzer as the documents, and the query is built
 * from the resulting terms. It is never parsed as Lucene syntax, so field names, wildcards,
 * quotes or boolean operators written by a model are plain search terms and cannot fail or reach
 * another field.</p>
 */
public final class LuceneToolSearchIndex implements ToolSearchIndex {

    static final String NAME_KEYWORD = "name_kw";
    static final String NAME_SORT = "name_sort";
    static final String TAG = "tag";
    static final String READ_ONLY = "readOnly";

    /**
     * Text fields and their boosts: a match on the name weighs most, on parameters least. The
     * order is fixed, so scores are summed in the same order on every start and near ties never
     * swap between restarts.
     */
    static final Map<String, Float> TEXT_FIELDS = textFields();

    private static Map<String, Float> textFields() {
        Map<String, Float> fields = new LinkedHashMap<>();
        fields.put("name", 4.0f);
        fields.put("title", 3.0f);
        fields.put("tags", 2.0f);
        fields.put("description", 1.5f);
        fields.put("params", 1.0f);
        return Collections.unmodifiableMap(fields);
    }

    /** Boost of an exact tool name, so a model that already knows the name gets that tool. */
    static final float EXACT_NAME_BOOST = 10.0f;

    private final Analyzer analyzer;
    private final IndexSearcher searcher;

    /**
     * Builds the index.
     *
     * @param documents the searchable tools
     */
    public LuceneToolSearchIndex(List<ToolSearchDocument> documents) {
        this.analyzer = newAnalyzer();
        ByteBuffersDirectory directory = new ByteBuffersDirectory();
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            for (ToolSearchDocument document : documents) {
                writer.addDocument(toLucene(document));
            }
            writer.commit();
            this.searcher = new IndexSearcher(DirectoryReader.open(directory));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot build the MCP tool search index", e);
        }
    }

    /**
     * The analyzer for both documents and queries: words split on spaces, punctuation, hyphens,
     * underscores and case changes, so {@code restart-deployment}, {@code restartDeployment} and
     * {@code restart_deployment} give the same terms, then lower-cased and stemmed.
     */
    static Analyzer newAnalyzer() {
        try {
            return CustomAnalyzer.builder()
                    .withTokenizer(StandardTokenizerFactory.class)
                    .addTokenFilter(WordDelimiterGraphFilterFactory.class,
                            "generateWordParts", "1",
                            "generateNumberParts", "1",
                            "splitOnCaseChange", "1",
                            "splitOnNumerics", "0",
                            "preserveOriginal", "0")
                    .addTokenFilter(FlattenGraphFilterFactory.class)
                    .addTokenFilter(LowerCaseFilterFactory.class)
                    .addTokenFilter(EnglishPossessiveFilterFactory.class)
                    .addTokenFilter(PorterStemFilterFactory.class)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot build the MCP tool search analyzer", e);
        }
    }

    private static Document toLucene(ToolSearchDocument tool) {
        Document document = new Document();
        document.add(new StringField(NAME_KEYWORD, tool.name(), Field.Store.YES));
        document.add(new SortedDocValuesField(NAME_SORT, new BytesRef(tool.name())));
        document.add(new TextField("name", tool.name(), Field.Store.NO));
        addText(document, "title", tool.title());
        addText(document, "description", tool.description());
        addText(document, "params", tool.parameters());
        for (String tag : tool.tags()) {
            document.add(new StringField(TAG, tag, Field.Store.NO));
            document.add(new TextField("tags", tag, Field.Store.NO));
        }
        document.add(new StringField(READ_ONLY, Boolean.toString(tool.readOnly()), Field.Store.NO));
        return document;
    }

    private static void addText(Document document, String field, String value) {
        if (value != null && !value.isBlank()) {
            document.add(new TextField(field, value, Field.Store.NO));
        }
    }

    @Override
    public List<String> search(ToolSearchQuery query) {
        BooleanQuery luceneQuery = buildQuery(query);
        if (luceneQuery == null) {
            return List.of();
        }
        try {
            StoredFields stored = searcher.storedFields();
            List<String> names = new ArrayList<>(query.limit());
            // An exact tool name comes first whatever the BM25 scores, when the filters keep it.
            ScoreDoc[] exact = searcher.search(exactNameQuery(query), 1).scoreDocs;
            String exactName = null;
            if (exact.length > 0) {
                exactName = stored.document(exact[0].doc).get(NAME_KEYWORD);
                names.add(exactName);
            }
            Sort sort = new Sort(SortField.FIELD_SCORE,
                    new SortField(NAME_SORT, SortField.Type.STRING));
            ScoreDoc[] hits = searcher.search(luceneQuery, query.limit(), sort).scoreDocs;
            for (ScoreDoc hit : hits) {
                String name = stored.document(hit.doc).get(NAME_KEYWORD);
                // Each hit is a distinct tool: only the exact name put first can repeat.
                if (names.size() < query.limit() && !name.equals(exactName)) {
                    names.add(name);
                }
            }
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException("MCP tool search failed", e);
        }
    }

    /**
     * Builds the query: one optional clause per analysed term and text field, plus an exact name
     * match, with at least one of them required; the tag and read-only filters are mandatory and
     * do not score. Returns {@code null} when the text has no searchable term. An exact name is
     * also ranked first by {@link #search}, whatever the scores.
     */
    BooleanQuery buildQuery(ToolSearchQuery query) {
        Set<String> terms = analyze(query.text());
        if (terms.isEmpty()) {
            return null;
        }
        // Lucene caps the clauses of one query: keep the first terms that fit, next to the exact
        // name clause and the two filters, so a long query never fails.
        int maxTerms = (IndexSearcher.getMaxClauseCount() - 3) / TEXT_FIELDS.size();
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String term : terms.stream().limit(maxTerms).toList()) {
            for (Map.Entry<String, Float> field : TEXT_FIELDS.entrySet()) {
                builder.add(new BoostQuery(new TermQuery(new Term(field.getKey(), term)),
                        field.getValue()), Occur.SHOULD);
            }
        }
        builder.add(new BoostQuery(new TermQuery(new Term(NAME_KEYWORD, query.text().strip())),
                EXACT_NAME_BOOST), Occur.SHOULD);
        builder.setMinimumNumberShouldMatch(1);
        addFilters(builder, query);
        return builder.build();
    }

    /** The tool whose name is exactly the query text, if the filters keep it. */
    static BooleanQuery exactNameQuery(ToolSearchQuery query) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new TermQuery(new Term(NAME_KEYWORD, query.text().strip())), Occur.MUST);
        addFilters(builder, query);
        return builder.build();
    }

    /** Adds the tag and read-only filters, which are mandatory and do not score. */
    private static void addFilters(BooleanQuery.Builder builder, ToolSearchQuery query) {
        if (!query.anyTags().isEmpty()) {
            // One clause whatever the number of tags, so a long tag list never fails.
            builder.add(new TermInSetQuery(TAG,
                    query.anyTags().stream().sorted().map(BytesRef::new).toList()), Occur.FILTER);
        }
        if (query.readOnlyOnly()) {
            builder.add(new TermQuery(new Term(READ_ONLY, "true")), Occur.FILTER);
        }
    }

    /** The distinct analysed terms of a text, in order of first appearance. */
    Set<String> analyze(String text) {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream stream = analyzer.tokenStream("query", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot analyse the MCP tool search query", e);
        }
        return terms;
    }
}
