/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.weblogger.ui.rendering.servlets;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WebloggerFactory;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.StaticTemplate;
import org.apache.roller.weblogger.pojos.Template;
import org.apache.roller.weblogger.pojos.TemplateRendition.TemplateLanguage;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.rendering.util.WeblogFeedRequest;
import org.apache.roller.weblogger.util.cache.CachedContent;
import org.apache.roller.weblogger.ui.rendering.Renderer;
import org.apache.roller.weblogger.ui.rendering.RendererManager;
import org.apache.roller.weblogger.ui.rendering.mobile.MobileDeviceRepository;
import org.apache.roller.weblogger.ui.rendering.model.ModelLoader;
import org.apache.roller.weblogger.ui.rendering.model.SearchResultsFeedModel;
import org.apache.roller.weblogger.ui.rendering.util.cache.SiteWideCache;
import org.apache.roller.weblogger.ui.rendering.util.cache.WeblogFeedCache;
import org.apache.roller.weblogger.ui.rendering.util.ModDateHeaderUtil;


/**
 * Responsible for rendering weblog feeds.
 */
public class FeedServlet extends HttpServlet {

    private static Log log = LogFactory.getLog(FeedServlet.class);

    private WeblogFeedCache weblogFeedCache = null;
    private SiteWideCache siteWideCache = null;


    /**
     * Init method for this servlet
     */
    public void init(ServletConfig servletConfig) throws ServletException {

        super.init(servletConfig);

        log.info("Initializing FeedServlet");

        // get a reference to the weblog feed cache
        this.weblogFeedCache = WeblogFeedCache.getInstance();

        // get a reference to the site wide cache
        this.siteWideCache = SiteWideCache.getInstance();
    }


    /**
     * Handle GET requests for weblog feeds.
     */
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        log.debug("Entering");

        Weblog weblog;
        boolean isSiteWide;

        WeblogFeedRequest feedRequest;
        try {
            // parse the incoming request and extract the relevant data
            feedRequest = new WeblogFeedRequest(request);

            weblog = feedRequest.getWeblog();
            if (weblog == null) {
                throw new WebloggerException("unable to lookup weblog: "
                        + feedRequest.getWeblogHandle());
            }

            // is this the site-wide weblog?
            isSiteWide = WebloggerRuntimeConfig.isSiteWideWeblog(feedRequest
                    .getWeblogHandle());

        } catch (Exception e) {
            // invalid feed request format or weblog doesn't exist
            log.debug("error creating weblog feed request", e);
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // determine the lastModified date for this content
        long lastModified = System.currentTimeMillis();
        if (isSiteWide) {
            lastModified = siteWideCache.getLastModified().getTime();
        } else if (weblog.getLastModified() != null) {
            lastModified = weblog.getLastModified().getTime();
        }

        // Respond with 304 Not Modified if it is not modified.
        if (ModDateHeaderUtil.respondIfNotModified(request, response,
                lastModified, feedRequest.getDeviceType())) {
            return;
        }

        // set last-modified date
        ModDateHeaderUtil.setLastModifiedHeader(response, lastModified,
                feedRequest.getDeviceType());

        // set content type
        String accepts = request.getHeader("Accept");
        String userAgent = request.getHeader("User-Agent");
        if (WebloggerRuntimeConfig
                .getBooleanProperty("site.newsfeeds.styledFeeds")
                && accepts != null
                && accepts.contains("*/*")
                && userAgent != null && userAgent.startsWith("Mozilla")) {
            // client is a browser and feed style is enabled so we want
            // browsers to load the page rather than popping up the download
            // dialog, so we provide a content-type that browsers will display
            response.setContentType("text/xml");
        } else if ("rss".equals(feedRequest.getFormat())) {
            response.setContentType("application/rss+xml; charset=utf-8");
        } else if ("atom".equals(feedRequest.getFormat())) {
            response.setContentType("application/atom+xml; charset=utf-8");
        }

        // generate cache key
        String cacheKey;
        if (isSiteWide) {
            cacheKey = siteWideCache.generateKey(feedRequest);
        } else {
            cacheKey = weblogFeedCache.generateKey(feedRequest);
        }

        // cached content checking
        CachedContent cachedContent;
        if (isSiteWide) {
            cachedContent = (CachedContent) siteWideCache.get(cacheKey);
        } else {
            cachedContent = (CachedContent) weblogFeedCache.get(cacheKey,
                    lastModified);
        }

        if (cachedContent != null) {
            log.debug("HIT " + cacheKey);

            response.setContentLength(cachedContent.getContent().length);
            response.getOutputStream().write(cachedContent.getContent());
            return;

        } else {
            log.debug("MISS " + cacheKey);
        }

        // validation. make sure that request input makes sense.
        boolean invalid = false;
        if (feedRequest.getLocale() != null
                && !feedRequest.getWeblog().isEnableMultiLang()) {
            invalid = true;
        }
        if (feedRequest.getWeblogCategoryName() != null) {

            // category specified. category must exist.
            if (feedRequest.getWeblogCategory() == null) {
                invalid = true;
            }

        } else if (feedRequest.getTags() != null
                && feedRequest.getTags().size() > 0) {

            try {
                // tags specified. make sure they exist.
                WeblogEntryManager wmgr = WebloggerFactory.getWeblogger()
                        .getWeblogEntryManager();
                invalid = !wmgr.getTagComboExists(feedRequest.getTags(),
                        (isSiteWide) ? null : weblog);
            } catch (WebloggerException ex) {
                invalid = true;
            }
        }

        if (invalid) {
            if (!response.isCommitted()) {
                response.reset();
            }
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // do we need to force a specific locale for the request?
        if (feedRequest.getLocale() == null && !weblog.isShowAllLangs()) {
            feedRequest.setLocale(weblog.getLocale());
        }

        // looks like we need to render content
        HashMap<String, Object> model = new HashMap<String, Object>();
        String pageId;
        try {
            // determine what template to render with
            boolean siteWide = WebloggerRuntimeConfig.isSiteWideWeblog(weblog
                    .getHandle());
            if (siteWide && "entries".equals(feedRequest.getType())
                    && feedRequest.getTerm() != null) {
                pageId = "site-search-atom.vm";

            } else if ("entries".equals(feedRequest.getType())
                    && feedRequest.getTerm() != null) {
                pageId = "feeds/weblog-search-atom.vm";

            } else if (siteWide) {
                pageId = "site-" + feedRequest.getType() + "-"
                        + feedRequest.getFormat() + ".vm";

            } else {
                pageId = "weblog-" + feedRequest.getType() + "-"
                        + feedRequest.getFormat() + ".vm";
            }

            // populate the rendering model
            Map<String, Object> initData = new HashMap<String, Object>();
            initData.put("parsedRequest", feedRequest);

            // define url strategy
            initData.put("urlStrategy", WebloggerFactory.getWeblogger()
                    .getUrlStrategy());

            // Load models for feeds
            String feedModels = WebloggerConfig
                    .getProperty("rendering.feedModels");
            ModelLoader.loadModels(feedModels, model, initData, true);

            // Load special models for site-wide blog

            if (siteWide) {
                String siteModels = WebloggerConfig
                        .getProperty("rendering.siteModels");
                ModelLoader.loadModels(siteModels, model, initData, true);
            }

            // Load search models if search feed
            if ("entries".equals(feedRequest.getType())
                    && feedRequest.getTerm() != null) {
                ModelLoader.loadModels(SearchResultsFeedModel.class.getName(),
                        model, initData, true);
            }

        } catch (WebloggerException ex) {
            log.error("ERROR loading model for page", ex);

            if (!response.isCommitted()) {
                response.reset();
            }
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // lookup Renderer we are going to use
        Renderer renderer;
        try {
            log.debug("Looking up renderer");
            Template template = new StaticTemplate(pageId, TemplateLanguage.VELOCITY);
            renderer = RendererManager.getRenderer(template,
                    MobileDeviceRepository.DeviceType.standard);
        } catch (Exception e) {
            // nobody wants to render my content :(

            // TODO: this log message has been disabled because it fills up
            // the logs with useless errors due to the fact that the way these
            // template ids are formed comes directly from the request and it
            // often gets bunk data causing invalid template ids.
            // at some point we should have better validation on the input so
            // that we can quickly dispatch invalid feed requests and only
            // get this far if we expect the template to be found
            // log.error("Couldn't find renderer for page "+pageId, e);

            if (!response.isCommitted()) {
                response.reset();
            }
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // render content. use default size of 24K for a standard page
        CachedContent rendererOutput = new CachedContent(RollerConstants.TWENTYFOUR_KB_IN_BYTES);
        try {
            log.debug("Doing rendering");
            renderer.render(model, rendererOutput.getCachedWriter());

            // flush rendered output and close
            rendererOutput.flush();
            rendererOutput.close();
        } catch (Exception e) {
            // bummer, error during rendering
            log.error("Error during rendering for page " + pageId, e);

            if (!response.isCommitted()) {
                response.reset();
            }
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // post rendering process

        // flush rendered content to response
        log.debug("Flushing response output");
        response.setContentLength(rendererOutput.getContent().length);
        response.getOutputStream().write(rendererOutput.getContent());

        // cache rendered content. only cache if user is not logged in?
        log.debug("PUT " + cacheKey);
        if (isSiteWide) {
            siteWideCache.put(cacheKey, rendererOutput);
        } else {
            weblogFeedCache.put(cacheKey, rendererOutput);
        }

        log.debug("Exiting");
    }

}
