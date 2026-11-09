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
package io.ikanos.spec.consumes.http;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How a consumed HTTP operation pages: the {@code pagination} block of an operation, a resource
 * or an adapter (HTTP Pagination design doc §3).
 *
 * <p>A block on the operation wins over the resource's, which wins over the adapter's, field by
 * field; a sub-block ({@code cursor}, {@code page}, ...) is taken whole from the narrowest level
 * that declares it. An operation pages only when its resolved block has a {@code style}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HttpClientPaginationSpec {

    /** Default of {@code maxPages}: the pages one drain fetches at most, the first included. */
    public static final int DEFAULT_MAX_PAGES = 20;

    /** Default of {@code maxItems}: the items one drain collects at most. */
    public static final int DEFAULT_MAX_ITEMS = 10000;

    private String style;
    private String items;
    private Cursor cursor;
    private NextLink nextLink;
    private Page page;
    private Offset offset;
    private PageSize pageSize;
    private Integer maxPages;
    private Integer maxItems;

    /**
     * Resolves the blocks of the adapter, the resource and the operation, broadest first, into
     * the operation's own; {@code null} when none declares anything.
     */
    public static HttpClientPaginationSpec resolve(HttpClientPaginationSpec... broadestFirst) {
        HttpClientPaginationSpec resolved = null;
        for (HttpClientPaginationSpec level : broadestFirst) {
            if (level == null) {
                continue;
            }
            if (resolved == null) {
                resolved = new HttpClientPaginationSpec();
            }
            resolved.style = level.style != null ? level.style : resolved.style;
            resolved.items = level.items != null ? level.items : resolved.items;
            resolved.cursor = level.cursor != null ? level.cursor : resolved.cursor;
            resolved.nextLink = level.nextLink != null ? level.nextLink : resolved.nextLink;
            resolved.page = level.page != null ? level.page : resolved.page;
            resolved.offset = level.offset != null ? level.offset : resolved.offset;
            resolved.pageSize = level.pageSize != null ? level.pageSize : resolved.pageSize;
            resolved.maxPages = level.maxPages != null ? level.maxPages : resolved.maxPages;
            resolved.maxItems = level.maxItems != null ? level.maxItems : resolved.maxItems;
        }
        return resolved;
    }

    /** The resolved {@code maxPages}, its default when absent. */
    public int maxPagesOrDefault() {
        return maxPages != null ? maxPages : DEFAULT_MAX_PAGES;
    }

    /** The resolved {@code maxItems}, its default when absent. */
    public int maxItemsOrDefault() {
        return maxItems != null ? maxItems : DEFAULT_MAX_ITEMS;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public String getItems() {
        return items;
    }

    public void setItems(String items) {
        this.items = items;
    }

    public Cursor getCursor() {
        return cursor;
    }

    public void setCursor(Cursor cursor) {
        this.cursor = cursor;
    }

    public NextLink getNextLink() {
        return nextLink;
    }

    public void setNextLink(NextLink nextLink) {
        this.nextLink = nextLink;
    }

    public Page getPage() {
        return page;
    }

    public void setPage(Page page) {
        this.page = page;
    }

    public Offset getOffset() {
        return offset;
    }

    public void setOffset(Offset offset) {
        this.offset = offset;
    }

    public PageSize getPageSize() {
        return pageSize;
    }

    public void setPageSize(PageSize pageSize) {
        this.pageSize = pageSize;
    }

    public Integer getMaxPages() {
        return maxPages;
    }

    public void setMaxPages(Integer maxPages) {
        this.maxPages = maxPages;
    }

    public Integer getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(Integer maxItems) {
        this.maxItems = maxItems;
    }

    /** {@code style: cursor}: a token from each page, sent back as a parameter or a header. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Cursor {
        private Object from;
        private String param;
        private String header;

        /** A JSONPath string, or a map with a {@code header} key. */
        public Object getFrom() {
            return from;
        }

        public void setFrom(Object from) {
            this.from = from;
        }

        public String getParam() {
            return param;
        }

        public void setParam(String param) {
            this.param = param;
        }

        public String getHeader() {
            return header;
        }

        public void setHeader(String header) {
            this.header = header;
        }
    }

    /** {@code style: nextLink}: the next page's URL, from the {@code Link} header or the body. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NextLink {
        private Object from;

        /** A JSONPath string, or a map with a {@code header} key. */
        public Object getFrom() {
            return from;
        }

        public void setFrom(Object from) {
            this.from = from;
        }
    }

    /** {@code style: page}: a page number, from {@code start}, sent as {@code param}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Page {
        private String param;
        private Integer start;
        private Object total;

        public String getParam() {
            return param;
        }

        public void setParam(String param) {
            this.param = param;
        }

        public Integer getStart() {
            return start;
        }

        public void setStart(Integer start) {
            this.start = start;
        }

        /** The total number of pages: a JSONPath string, or a map with a {@code header} key. */
        public Object getTotal() {
            return total;
        }

        public void setTotal(Object total) {
            this.total = total;
        }
    }

    /** {@code style: offset}: an offset from 0, advanced by the items received. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Offset {
        private String param;
        private Object total;

        public String getParam() {
            return param;
        }

        public void setParam(String param) {
            this.param = param;
        }

        /** The total number of items: a JSONPath string, or a map with a {@code header} key. */
        public Object getTotal() {
            return total;
        }

        public void setTotal(Object total) {
            this.total = total;
        }
    }

    /** The page size the upstream is asked for, and the parameter that asks it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PageSize {
        private String param;
        private Integer value;

        public String getParam() {
            return param;
        }

        public void setParam(String param) {
            this.param = param;
        }

        public Integer getValue() {
            return value;
        }

        public void setValue(Integer value) {
            this.value = value;
        }
    }
}
