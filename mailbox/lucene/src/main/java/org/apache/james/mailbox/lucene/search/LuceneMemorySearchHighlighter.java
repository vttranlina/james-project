///****************************************************************
// * Licensed to the Apache Software Foundation (ASF) under one   *
// * or more contributor license agreements.  See the NOTICE file *
// * distributed with this work for additional information        *
// * regarding copyright ownership.  The ASF licenses this file   *
// * to you under the Apache License, Version 2.0 (the            *
// * "License"); you may not use this file except in compliance   *
// * with the License.  You may obtain a copy of the License at   *
// *                                                              *
// *   http://www.apache.org/licenses/LICENSE-2.0                 *
// *                                                              *
// * Unless required by applicable law or agreed to in writing,   *
// * software distributed under the License is distributed on an  *
// * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
// * KIND, either express or implied.  See the License for the    *
// * specific language governing permissions and limitations      *
// * under the License.                                           *
// ****************************************************************/
//
//package org.apache.james.mailbox.lucene.search;
//
//import org.apache.commons.lang3.StringUtils;
//import org.apache.james.mailbox.MailboxManager;
//import org.apache.james.mailbox.MailboxSession;
//import org.apache.james.mailbox.MessageIdManager;
//import org.apache.james.mailbox.model.FetchGroup;
//import org.apache.james.mailbox.model.MessageId;
//import org.apache.james.mailbox.model.MessageResult;
//import org.apache.james.mailbox.model.MultimailboxesSearchQuery;
//import org.apache.james.mailbox.store.search.SearchHighlighter;
//import org.apache.james.mailbox.store.search.SearchSnippet;
//import org.apache.james.mime4j.codec.DecodeMonitor;
//import org.apache.james.mime4j.dom.Message;
//import org.apache.james.mime4j.message.BasicBodyFactory;
//import org.apache.james.mime4j.message.DefaultMessageBuilder;
//import org.apache.james.mime4j.stream.MimeConfig;
//import org.apache.james.util.html.HtmlTextExtractor;
//import org.apache.james.util.mime.MessageContentExtractor;
//import org.reactivestreams.Publisher;
//
//import java.io.Closeable;
//import java.io.IOException;
//import java.util.ArrayList;
//import java.util.List;
//
//import org.apache.lucene.analysis.Analyzer;
//import org.apache.lucene.analysis.TokenStream;
//import org.apache.lucene.analysis.standard.StandardAnalyzer;
//import org.apache.lucene.document.Document;
//import org.apache.lucene.document.Field;
//import org.apache.lucene.document.StringField;
//import org.apache.lucene.document.TextField;
//import org.apache.lucene.index.DirectoryReader;
//import org.apache.lucene.index.IndexWriter;
//import org.apache.lucene.index.IndexWriterConfig;
//import org.apache.lucene.queryparser.classic.ParseException;
//import org.apache.lucene.queryparser.classic.QueryParser;
//import org.apache.lucene.search.IndexSearcher;
//import org.apache.lucene.search.Query;
//import org.apache.lucene.search.ScoreDoc;
//import org.apache.lucene.search.TopDocs;
//import org.apache.lucene.search.highlight.Formatter;
//import org.apache.lucene.search.highlight.Fragmenter;
//import org.apache.lucene.search.highlight.Highlighter;
//import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
//import org.apache.lucene.search.highlight.QueryScorer;
//import org.apache.lucene.search.highlight.SimpleFragmenter;
//import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
//import org.apache.lucene.search.highlight.TokenSources;
//import org.apache.lucene.store.ByteBuffersDirectory;
//import org.apache.lucene.store.Directory;
//
//import com.github.fge.lambdas.Throwing;
//
//import reactor.core.Disposable;
//import reactor.core.publisher.Flux;
//
//public class LuceneMemorySearchHighlighter implements SearchHighlighter {
//
//    private final MailboxManager mailboxManager;
//    private final MessageIdManager messageIdManager;
//    private final MessageContentExtractor messageContentExtractor;
//    private final HtmlTextExtractor htmlTextExtractor;
//    private final IndexWriter indexWriter;
//
//    public LuceneMemorySearchHighlighter(MailboxManager mailboxManager,
//                                         MessageIdManager messageIdManager,
//                                         MessageContentExtractor messageContentExtractor,
//                                         HtmlTextExtractor htmlTextExtractor) {
//        this.mailboxManager = mailboxManager;
//        this.messageIdManager = messageIdManager;
//        this.messageContentExtractor = messageContentExtractor;
//        this.htmlTextExtractor = htmlTextExtractor;
//
//        // Initialize an in-memory index using ByteBuffersDirectory
//        Directory directory = new ByteBuffersDirectory();
//        Analyzer analyzer = new StandardAnalyzer();
//        IndexWriterConfig config = new IndexWriterConfig(analyzer);
//        this.indexWriter = Throwing.supplier(() -> new IndexWriter(directory, config)).get();
//    }
//
//    @Override
//    public Flux<SearchSnippet> highlightSearch(MultimailboxesSearchQuery expression, MailboxSession session, long limit) {
//        var search = Flux.from(mailboxManager.search(expression, session, limit))
//            .collectList()
//            .flatMapMany(messageIds -> messageIdManager.getMessagesReactive(messageIds, FetchGroup.FULL_CONTENT, session))
//            .doOnNext(messageResult -> Throwing.runnable(() -> indexDocument(indexWriter, messageResult)).run());
//
//
//        indexWriter.close();
//
//        // Search and highlight results
//        String queryText = "Lucene";
//        QueryParser parser = new QueryParser("body", analyzer); // Search in the body field
//        Query query = parser.parse(queryText);
//
//        DirectoryReader directoryReader = DirectoryReader.open(directory);
//        IndexSearcher searcher = new IndexSearcher(directoryReader);
//        TopDocs topDocs = searcher.search(query, 10);
//
//        // Highlighter setup
//        Formatter formatter = new SimpleHTMLFormatter("<mark>", "</mark>");
//        QueryScorer scorer = new QueryScorer(query);
//        Highlighter highlighter = new Highlighter(formatter, scorer);
//        Fragmenter fragmenter = new SimpleFragmenter(255); // Fragment size
//        highlighter.setTextFragmenter(fragmenter);
//
//        // Create a list to store the search snippets
//        List<SearchSnippet> searchSnippets = new ArrayList<>();
//
//        // Display highlighted results for both body and subject fields
//        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
//            Document doc = searcher.doc(scoreDoc.doc);
//
//            // Highlight the subject field
//            String subject = doc.get("subject");
//            TokenStream subjectTokenStream = TokenSources.getTokenStream(doc, "subject", analyzer);
//            String highlightedSubject = highlighter.getBestFragment(subjectTokenStream, subject);
//
//            // Highlight the body field
//            String body = doc.get("body");
//            TokenStream bodyTokenStream = TokenSources.getTokenStream(doc, "body", analyzer);
//            String highlightedBody = highlighter.getBestFragment(bodyTokenStream, body);
//
//            // Create a SearchSnippet object and add it to the list
//            SearchSnippet snippet = new SearchSnippet(doc.get("id"), highlightedSubject, highlightedBody);
//            searchSnippets.add(snippet);
//        }
//
//        // Print out the search snippets
//        for (SearchSnippet snippet : searchSnippets) {
//            System.out.println(snippet);
//        }
//
//        // Result:
//        // SearchSnippet{id='1', highlightedSubject='<mark>Lucene</mark> is a powerful search library.', highlightedBody='<mark>Lucene</mark> is widely used for text search. <mark>Lucene</mark> can handle complex queries.'}
//        // SearchSnippet{id='2', highlightedSubject='<mark>Lucene</mark> Search Features', highlightedBody='Searching and indexing are supported by <mark>Lucene</mark>.'}
//
//        directoryReader.close();
//
//        return null;
//    }
//
//    private void indexDocument(IndexWriter indexWriter, MessageResult messageResult) throws IOException {
//        Document doc = new Document();
//        DefaultMessageBuilder defaultMessageBuilder = new DefaultMessageBuilder();
//        defaultMessageBuilder.setMimeEntityConfig(MimeConfig.PERMISSIVE);
//        defaultMessageBuilder.setDecodeMonitor(DecodeMonitor.SILENT);
//        defaultMessageBuilder.setBodyFactory(new BasicBodyFactory());
//
//        Message mime4JMessage = Throwing.supplier(() -> defaultMessageBuilder.parseMessage(messageResult.getFullContent().getInputStream())).get();
//        MessageContentExtractor.MessageContent extract = messageContentExtractor.extract(mime4JMessage);
//
//        doc.add(new StringField("id", messageResult.getMessageId().serialize(), Field.Store.YES));
//        doc.add(new TextField("subject", mime4JMessage.getSubject(), Field.Store.YES));
//        doc.add(new TextField("body", extract.extractMainTextContent(htmlTextExtractor).orElse(StringUtils.EMPTY), Field.Store.YES));
//        Throwing.runnable(() -> indexWriter.addDocument(doc)).run();
//    }
//
//
//}
