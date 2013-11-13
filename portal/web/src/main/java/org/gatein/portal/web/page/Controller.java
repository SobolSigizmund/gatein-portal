/*
 * Copyright (C) 2012 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.gatein.portal.web.page;

import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.xml.namespace.QName;

import juzu.Param;
import juzu.Path;
import juzu.Resource;
import juzu.Response;
import juzu.Route;
import juzu.View;
import juzu.impl.common.JSON;
import juzu.impl.common.Lexers;
import juzu.impl.common.Tools;
import juzu.impl.request.Request;
import juzu.request.RequestParameter;
import juzu.request.ResourceContext;
import juzu.request.ViewContext;
import juzu.template.Template;
import org.gatein.portal.web.layout.Layout;
import org.gatein.portal.web.layout.RenderingContext;
import org.gatein.portal.web.layout.ZoneLayoutFactory;
import org.gatein.portal.mop.customization.CustomizationService;
import org.gatein.portal.mop.hierarchy.GenericScope;
import org.gatein.portal.mop.hierarchy.NodeContext;
import org.gatein.portal.mop.layout.ElementState;
import org.gatein.portal.mop.layout.LayoutService;
import org.gatein.portal.mop.navigation.NavigationContext;
import org.gatein.portal.mop.navigation.NavigationService;
import org.gatein.portal.mop.navigation.NodeState;
import org.gatein.portal.mop.page.PageKey;
import org.gatein.portal.mop.page.PageService;
import org.gatein.portal.mop.site.SiteContext;
import org.gatein.portal.mop.site.SiteKey;
import org.gatein.portal.mop.site.SiteService;
import org.gatein.portal.web.page.spi.ContentProvider;
import org.gatein.portal.web.page.spi.RenderTask;
import org.gatein.portal.web.page.spi.WindowContent;
import org.gatein.portal.web.page.spi.portlet.PortletContentProvider;

/**
 * The controller for aggregation.
 *
 * @author <a href="mailto:julien.viet@exoplatform.com">Julien Viet</a>
 */
public class Controller {
    /** . */
    private static final Map<String, String[]> NO_PARAMETERS = Collections.emptyMap();

    @Inject
    SiteService siteService;

    @Inject
    NavigationService navigationService;

    @Inject
    PageService pageService;

    @Inject
    LayoutService layoutService;

    @Inject
    CustomizationService customizationService;

    @Inject
    ZoneLayoutFactory layoutFactory;

    @Inject
    PortletContentProvider contentProvider;

    @Inject
    @Path("not_found.gtmpl")
    Template notFound;

    @View()
    @Route(value = "/{javax.portlet.path}", priority = 2)
    public Response index(
            ViewContext context,
            @Param(name = "javax.portlet.path", pattern = ".*")
            String path,
            @Param(name = "javax.portlet.a")
            String phase,
            @Param(name = "javax.portlet.t")
            String target,
            @Param(name = "javax.portlet.w")
            String targetWindowState,
            @Param(name = "javax.portlet.m")
            String targetMode) throws Exception {

        // Parse path
        List<String> names = new ArrayList<String>();
        for (String name : Tools.split(path, '/')) {
            if (name.length() > 0) {
                names.add(name);
            }
        }

        //
        NavigationContext navigation = navigationService.loadNavigation(SiteKey.portal("classic"));
        NodeContext<?, NodeState> root =  navigationService.loadNode(NodeState.model(), navigation, GenericScope.branchShape(names), null);
        if (root != null) {

            //
            Map<String, RequestParameter> requestParameters = context.getParameters();

            // Get our node from the navigation
            NodeContext<?, NodeState> current = root;
            for (String name : names) {
                current = current.get(name);
                if (current == null) {
                    break;
                }
            }
            if (current == null) {
                return notFound.with().set("path", path).notFound();
            } else {

                // Page builder
                PageContext.Builder pageBuilder = new PageContext.Builder(path);

                // Load site windows
                SiteContext site = siteService.loadSite(SiteKey.portal("classic"));
                NodeContext<org.gatein.portal.web.page.NodeState, ElementState> siteStructure = layoutService.loadLayout(pageBuilder.asModel(contentProvider, customizationService), site.getLayoutId(), null);

                // Load page windows
                NodeState state = current.getState();
                PageKey pageKey = state.getPageRef();
                org.gatein.portal.mop.page.PageContext page = pageService.loadPage(pageKey);
                NodeContext<org.gatein.portal.web.page.NodeState, ElementState> pageStructure = layoutService.loadLayout(pageBuilder.asModel(contentProvider, customizationService), page.getLayoutId(), null);

                // Decode from request
                Map<String, String[]> parameters = NO_PARAMETERS;
                for (RequestParameter parameter : requestParameters.values()) {
                    String name = parameter.getName();
                    if (name.startsWith("javax.portlet.")) {
                        if (name.equals("javax.portlet.p")) {
                            Decoder decoder = new Decoder(parameter.getRaw(0));
                            HashMap<QName, String[]> prp = new HashMap<QName, String[]>();
                            for (Map.Entry<String, String[]> p : decoder.decode().getParameters().entrySet()) {
                                prp.put(new QName(p.getKey()), p.getValue());
                            }
                            pageBuilder.setQNameParameters(prp);
                        } else if (name.startsWith("javax.portlet.p.")) {
                            String id = name.substring("javax.portlet.p.".length());
                            WindowContent window = pageBuilder.getWindow(id);
                            if (window != null) {
                                window.setParameters(parameter.getRaw(0));
                            }
                        } else if (name.startsWith("javax.portlet.w.")) {
                            String id = name.substring("javax.portlet.w.".length());
                            WindowContent window = pageBuilder.getWindow(id);
                            if (window != null) {
                                window.setWindowState(parameter.getValue());
                            }
                        } else if (name.startsWith("javax.portlet.m.")) {
                            String id = name.substring("javax.portlet.m.".length());
                            WindowContent window = pageBuilder.getWindow(id);
                            if (window != null) {
                                window.setMode(parameter.getValue());
                            }
                        }
                    } else {
                        if (parameters == NO_PARAMETERS) {
                            parameters = new HashMap<String, String[]>();
                        }
                        parameters.put(name, parameter.toArray());
                    }
                }

                //
                if (phase != null && !"edit".equals(phase)) {

                    //
                    PageContext pageContext = pageBuilder.build();

                    // Going to invoke process action
                    if (target != null) {
                        WindowContext window = pageContext.get(target);
                        if (window != null) {

                            if ("action".equals(phase)) {

                                //
                                String windowState = window.state.getWindowState();
                                if (targetWindowState != null) {
                                    windowState = targetWindowState;
                                }
                                String mode = window.state.getMode();
                                if (targetMode != null) {
                                    mode = targetMode;
                                }

                                //
                                return window.processAction(windowState, mode, parameters);
                            } else if ("resource".equals(phase)) {

                                //
                                String id;
                                RequestParameter resourceId = requestParameters.get("javax.portlet.r");
                                if (resourceId != null) {
                                    id = resourceId.getValue();
                                } else {
                                    id = null;
                                }

                                //
                                return window.serveResource(id, parameters);
                            } else {
                                throw new AssertionError("should not be here");
                            }
                        } else {
                            return Response.error("Target " + target + " not found");
                        }
                    } else {
                        return Response.error("No target");
                    }
                } else {

                    // Set page parameters
                    pageBuilder.setParameters(parameters);

                    // Build page
                    PageContext pageContext = pageBuilder.build();

                    // Render page
                    String layoutId = page.getState().getFactoryId();
                    if (layoutId == null) {
                        layoutId = "1";
                    }
                    Layout pageLayout = layoutFactory.build(layoutId, pageStructure);
                    Layout siteLayout = layoutFactory.build("site", siteStructure);

                    //
                    ReactivePage rp = new ReactivePage(
                            pageContext,
                            context.getUserContext().getLocale(),
                            new RenderingContext(path, page.getLayoutId(), page.getKey().format(), "edit".equals(phase)));

                    //
                    Response.Content content = (Response.Content)rp.execute(siteLayout, pageLayout, context);
                    if (phase != null && "edit".equals(phase)) {
                        content.withAssets("editor", "underscore", "backbone", "layout-model", "layout-view");
                    }
                    return content;
                }
            }
        } else {
            return Response.notFound("Page for navigation " + path + " could not be located");
        }
    }

    /**
     * Renders a single window in a specific page context.
     *
     * @param contentType the window content type
     * @param contentId the window content id
     * @param url the url
     */
    @Resource
    @Route("/window/{contentType}/{contentId}")
    public Response window(
            String contentType,
            @Param(pattern = ".*") String contentId,
            @Param(name = "javax.portlet.url") String url) throws Exception {

        // We need
        if (url == null) {
            return Response.status(400);
        }

        //
        String baseURL = Controller_.index("", null, null, null, null).toString();
        String prefix = new URL(baseURL).getPath();
        Pattern p = Pattern.compile("^https?://[^/]+(/.*)$");
        Matcher m = p.matcher(url);
        String path;
        if (m.matches()) {
            path = m.group(1);
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length());
            } else {
                return Response.status(400);
            }
        } else {
            return Response.status(400);
        }

        // Locate content first
        ContentProvider contentProvider;
        if (contentType.equals("portlet")) {
            contentProvider = this.contentProvider;
        } else {
            return Response.notFound("Invalid content type " + contentType);
        }

        // Locate the content
        WindowContent content = contentProvider.getContent(contentId);
        if (content == null) {
            return Response.notFound("Invalid content id " + contentId);
        }

        // Parse path
        PageContext.Builder pageBuilder;
        int a = path.indexOf('?');
        Map<String, String[]> parameters = NO_PARAMETERS;
        if (a >= 0) {
            String query = path.substring(a + 1);
            path = path.substring(0, a);
            pageBuilder = new PageContext.Builder(path);
            for (Iterator<RequestParameter> i = Lexers.queryParser(query);i.hasNext();) {
                RequestParameter parameter = i.next();
                String name = parameter.getName();
                if (name.startsWith("javax.portlet.")) {
                    if (name.startsWith("javax.portlet.p.")) {
                        String id = name.substring("javax.portlet.p.".length());
                        WindowContent window = pageBuilder.getWindow(id);
                        if (window != null) {
                            window.setParameters(parameter.getRaw(0));
                        }
                    }
                } else {
                    if (parameters == NO_PARAMETERS) {
                        parameters = new HashMap<String, String[]>();
                    }
                    parameters.put(name, parameter.toArray());
                }
            }
        } else {
            pageBuilder = new PageContext.Builder(path);
        }
        pageBuilder.setParameters(parameters);

        // Set our window
        String name = UUID.randomUUID().toString();
        pageBuilder.setWindow(name, content);

        // Build page
        PageContext page = pageBuilder.build();

        // Create render task
        WindowContext window = page.get(name);
        RenderTask task = content.createRender(window);

        //
        Result result = task.execute(Request.getCurrent().getUserContext().getLocale());
        if (result instanceof Result.Fragment) {
            Result.Fragment fragment = (Result.Fragment) result;
            //200 OK
            return Response.status(200).
                    body(new JSON().
                            set("title", fragment.title).
                            set("content", fragment.content).
                            set("name", name).toString()
                    ).
                    withCharset(Charset.forName("UTF-8")).
                    withMimeType("application/json");
        } else {
            //501 Not Implemented
            return Response.status(501).body("Not yet handled " + result).withCharset(Charset.forName("UTF-8"))
                    .withMimeType("application/json");
        }
    }
}
