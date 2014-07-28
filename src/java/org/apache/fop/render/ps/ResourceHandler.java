/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* $Id$ */

package org.apache.fop.render.ps;

import java.awt.geom.Dimension2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.xmlgraphics.image.loader.ImageException;
import org.apache.xmlgraphics.image.loader.ImageFlavor;
import org.apache.xmlgraphics.image.loader.ImageInfo;
import org.apache.xmlgraphics.image.loader.ImageManager;
import org.apache.xmlgraphics.image.loader.ImageSessionContext;
import org.apache.xmlgraphics.image.loader.util.ImageUtil;
import org.apache.xmlgraphics.ps.DSCConstants;
import org.apache.xmlgraphics.ps.FormGenerator;
import org.apache.xmlgraphics.ps.PSGenerator;
import org.apache.xmlgraphics.ps.PSResource;
import org.apache.xmlgraphics.ps.dsc.DSCException;
import org.apache.xmlgraphics.ps.dsc.DSCFilter;
import org.apache.xmlgraphics.ps.dsc.DSCListener;
import org.apache.xmlgraphics.ps.dsc.DSCParser;
import org.apache.xmlgraphics.ps.dsc.DSCParserConstants;
import org.apache.xmlgraphics.ps.dsc.DefaultNestedDocumentHandler;
import org.apache.xmlgraphics.ps.dsc.ResourceTracker;
import org.apache.xmlgraphics.ps.dsc.events.DSCComment;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentBoundingBox;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentDocumentNeededResources;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentDocumentSuppliedResources;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentHiResBoundingBox;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentIncludeResource;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentLanguageLevel;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentPage;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentPages;
import org.apache.xmlgraphics.ps.dsc.events.DSCEvent;
import org.apache.xmlgraphics.ps.dsc.events.DSCHeaderComment;
import org.apache.xmlgraphics.ps.dsc.events.PostScriptComment;
import org.apache.xmlgraphics.ps.dsc.events.PostScriptLine;
import org.apache.xmlgraphics.ps.dsc.tools.DSCTools;

import org.apache.fop.ResourceEventProducer;
import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.fonts.FontInfo;
import org.apache.fop.render.ImageHandler;
import org.apache.fop.render.ImageHandlerRegistry;

/**
 * This class is used when two-pass production is used to generate the PostScript file (setting
 * "optimize-resources"). It uses the DSC parser from XML Graphics Commons to go over the
 * temporary file generated by the PSRenderer and adds all used fonts and images as resources
 * to the PostScript file.
 */
public class ResourceHandler implements DSCParserConstants, PSSupportedFlavors {

    /** logging instance */
    private static Log log = LogFactory.getLog(ResourceHandler.class);

    private FOUserAgent userAgent;
    private FontInfo fontInfo;

    private PSEventProducer eventProducer;

    private ResourceTracker resTracker;

    //key: URI, values PSImageFormResource
    private Map globalFormResources = new java.util.HashMap();
    //key: PSResource, values PSImageFormResource
    private Map inlineFormResources = new java.util.HashMap();

    /**
     * Main constructor.
     * @param userAgent the FO user agent
     * @param eventProducer the event producer
     * @param fontInfo the font information
     * @param resTracker the resource tracker to use
     * @param formResources Contains all forms used by this document (maintained by PSRenderer)
     */
    public ResourceHandler(FOUserAgent userAgent, PSEventProducer eventProducer,
            FontInfo fontInfo, ResourceTracker resTracker, Map formResources) {
        this.userAgent = userAgent;
        this.eventProducer = eventProducer;
        this.fontInfo = fontInfo;
        this.resTracker = resTracker;
        determineInlineForms(formResources);
    }

    /**
     * This method splits up the form resources map into two. One for global forms which
     * have been referenced more than once, and one for inline forms which have only been
     * used once. The latter is to conserve memory in the PostScript interpreter.
     * @param formResources the original form resources map
     */
    private void determineInlineForms(Map formResources) {
        if (formResources == null) {
            return;
        }
        Iterator iter = formResources.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry entry = (Map.Entry)iter.next();
            PSResource res = (PSResource)entry.getValue();
            long count = resTracker.getUsageCount(res);
            if (count > 1) {
                //Make global form
                this.globalFormResources.put(entry.getKey(), res);
            } else {
                //Inline resource
                this.inlineFormResources.put(res, res);
                resTracker.declareInlined(res);
            }
        }
    }

    /**
     * Rewrites the temporary PostScript file generated by PSRenderer adding all needed resources
     * (fonts and images).
     * @param in the InputStream for the temporary PostScript file
     * @param out the OutputStream to write the finished file to
     * @param pageCount the number of pages (given here because PSRenderer writes an "(atend)")
     * @param documentBoundingBox the document's bounding box
     *                                  (given here because PSRenderer writes an "(atend)")
     * @throws DSCException If there's an error in the DSC structure of the PS file
     * @throws IOException In case of an I/O error
     */
    public void process(InputStream in, OutputStream out,
            int pageCount, Rectangle2D documentBoundingBox)
                    throws DSCException, IOException {
        DSCParser parser = new DSCParser(in);
        parser.setCheckEOF(false);

        PSGenerator gen = new PSGenerator(out);
        parser.addListener(new DefaultNestedDocumentHandler(gen));
        parser.addListener(new IncludeResourceListener(gen));

        //Skip DSC header
        DSCHeaderComment header = DSCTools.checkAndSkipDSC30Header(parser);
        header.generate(gen);

        parser.setFilter(new DSCFilter() {
            private final Set filtered = new java.util.HashSet();
            {
                //We rewrite those as part of the processing
                filtered.add(DSCConstants.PAGES);
                filtered.add(DSCConstants.BBOX);
                filtered.add(DSCConstants.HIRES_BBOX);
                filtered.add(DSCConstants.DOCUMENT_NEEDED_RESOURCES);
                filtered.add(DSCConstants.DOCUMENT_SUPPLIED_RESOURCES);
            }
            public boolean accept(DSCEvent event) {
                if (event.isDSCComment()) {
                    //Filter %%Pages which we add manually from a parameter
                    return !(filtered.contains(event.asDSCComment().getName()));
                } else {
                    return true;
                }
            }
        });

        //Get PostScript language level (may be missing)
        while (true) {
            DSCEvent event = parser.nextEvent();
            if (event == null) {
                reportInvalidDSC();
            }
            if (DSCTools.headerCommentsEndHere(event)) {
                //Set number of pages
                DSCCommentPages pages = new DSCCommentPages(pageCount);
                pages.generate(gen);
                new DSCCommentBoundingBox(documentBoundingBox).generate(gen);
                new DSCCommentHiResBoundingBox(documentBoundingBox).generate(gen);

                PSFontUtils.determineSuppliedFonts(resTracker, fontInfo, fontInfo.getUsedFonts());
                registerSuppliedForms(resTracker, globalFormResources);

                //Supplied Resources
                DSCCommentDocumentSuppliedResources supplied
                    = new DSCCommentDocumentSuppliedResources(
                            resTracker.getDocumentSuppliedResources());
                supplied.generate(gen);

                //Needed Resources
                DSCCommentDocumentNeededResources needed
                    = new DSCCommentDocumentNeededResources(
                            resTracker.getDocumentNeededResources());
                needed.generate(gen);

                //Write original comment that ends the header comments
                event.generate(gen);
                break;
            }
            if (event.isDSCComment()) {
                DSCComment comment = event.asDSCComment();
                if (DSCConstants.LANGUAGE_LEVEL.equals(comment.getName())) {
                    DSCCommentLanguageLevel level = (DSCCommentLanguageLevel)comment;
                    gen.setPSLevel(level.getLanguageLevel());
                }
            }
            event.generate(gen);
        }

        //Skip to the FOPFontSetup
        PostScriptComment fontSetupPlaceholder = parser.nextPSComment("FOPFontSetup", gen);
        if (fontSetupPlaceholder == null) {
            throw new DSCException("Didn't find %FOPFontSetup comment in stream");
        }
        PSFontUtils.writeFontDict(gen, fontInfo, fontInfo.getUsedFonts(), eventProducer);
        generateForms(globalFormResources, gen);

        //Skip the prolog and to the first page
        DSCComment pageOrTrailer = parser.nextDSCComment(DSCConstants.PAGE, gen);
        if (pageOrTrailer == null) {
            throw new DSCException("Page expected, but none found");
        }

        //Process individual pages (and skip as necessary)
        while (true) {
            DSCCommentPage page = (DSCCommentPage)pageOrTrailer;
            page.generate(gen);
            pageOrTrailer = DSCTools.nextPageOrTrailer(parser, gen);
            if (pageOrTrailer == null) {
                reportInvalidDSC();
            } else if (!DSCConstants.PAGE.equals(pageOrTrailer.getName())) {
                pageOrTrailer.generate(gen);
                break;
            }
        }

        //Write the rest
        while (parser.hasNext()) {
            DSCEvent event = parser.nextEvent();
            event.generate(gen);
        }
        gen.flush();
    }

    private static void reportInvalidDSC() throws DSCException {
        throw new DSCException("File is not DSC-compliant: Unexpected end of file");
    }

    private static void registerSuppliedForms(ResourceTracker resTracker, Map formResources)
            throws IOException {
        if (formResources == null) {
            return;
        }
        Iterator iter = formResources.values().iterator();
        while (iter.hasNext()) {
            PSImageFormResource form = (PSImageFormResource)iter.next();
            resTracker.registerSuppliedResource(form);
        }
    }

    private void generateForms(Map formResources, PSGenerator gen) throws IOException {
        if (formResources == null) {
            return;
        }
        Iterator iter = formResources.values().iterator();
        while (iter.hasNext()) {
            PSImageFormResource form = (PSImageFormResource)iter.next();
            generateFormForImage(gen, form);
        }
    }

    private void generateFormForImage(PSGenerator gen, PSImageFormResource form)
                throws IOException {
        final String uri = form.getImageURI();

        ImageManager manager = userAgent.getImageManager();
        ImageInfo info = null;
        try {
            ImageSessionContext sessionContext = userAgent.getImageSessionContext();
            info = manager.getImageInfo(uri, sessionContext);

            //Create a rendering context for form creation
            PSRenderingContext formContext = new PSRenderingContext(
                    userAgent, gen, fontInfo, true);

            ImageFlavor[] flavors;
            ImageHandlerRegistry imageHandlerRegistry
                = userAgent.getImageHandlerRegistry();
            flavors = imageHandlerRegistry.getSupportedFlavors(formContext);

            Map hints = ImageUtil.getDefaultHints(sessionContext);
            org.apache.xmlgraphics.image.loader.Image img = manager.getImage(
                    info, flavors, hints, sessionContext);

            ImageHandler basicHandler = imageHandlerRegistry.getHandler(formContext, img);
            if (basicHandler == null) {
                throw new UnsupportedOperationException(
                        "No ImageHandler available for image: "
                            + img.getInfo() + " (" + img.getClass().getName() + ")");
            }

            if (!(basicHandler instanceof PSImageHandler)) {
                throw new IllegalStateException(
                        "ImageHandler implementation doesn't behave properly."
                        + " It should have returned false in isCompatible(). Class: "
                        + basicHandler.getClass().getName());
            }
            PSImageHandler handler = (PSImageHandler)basicHandler;
            if (log.isTraceEnabled()) {
                log.trace("Using ImageHandler: " + handler.getClass().getName());
            }
            handler.generateForm(formContext, img, form);

        } catch (ImageException ie) {
            ResourceEventProducer eventProducer = ResourceEventProducer.Provider.get(
                    userAgent.getEventBroadcaster());
            eventProducer.imageError(resTracker, (info != null ? info.toString() : uri),
                    ie, null);
        }
    }

    private static FormGenerator createMissingForm(String formName, final Dimension2D dimensions) {
        FormGenerator formGen = new FormGenerator(formName, null, dimensions) {

            protected void generatePaintProc(PSGenerator gen) throws IOException {
                gen.writeln("0 setgray");
                gen.writeln("0 setlinewidth");
                String w = gen.formatDouble(dimensions.getWidth());
                String h = gen.formatDouble(dimensions.getHeight());
                gen.writeln(w + " " + h  + " scale");
                gen.writeln("0 0 1 1 rectstroke");
                gen.writeln("newpath");
                gen.writeln("0 0 moveto");
                gen.writeln("1 1 lineto");
                gen.writeln("stroke");
                gen.writeln("newpath");
                gen.writeln("0 1 moveto");
                gen.writeln("1 0 lineto");
                gen.writeln("stroke");
            }

        };
        return formGen;
    }

    private class IncludeResourceListener implements DSCListener {

        private PSGenerator gen;

        public IncludeResourceListener(PSGenerator gen) {
            this.gen = gen;
        }

        /** {@inheritDoc} */
        public void processEvent(DSCEvent event, DSCParser parser)
                    throws IOException, DSCException {
            if (event.isDSCComment() && event instanceof DSCCommentIncludeResource) {
                DSCCommentIncludeResource include = (DSCCommentIncludeResource)event;
                PSResource res = include.getResource();
                if (res.getType().equals(PSResource.TYPE_FORM)) {
                    if (inlineFormResources.containsValue(res)) {
                        PSImageFormResource form = (PSImageFormResource)
                                    inlineFormResources.get(res);
                        //Create an inline form
                        //Wrap in save/restore pair to release memory
                        gen.writeln("save");
                        generateFormForImage(gen, form);
                        boolean execformFound = false;
                        DSCEvent next = parser.nextEvent();
                        if (next.isLine()) {
                            PostScriptLine line = next.asLine();
                            if (line.getLine().endsWith(" execform")) {
                                line.generate(gen);
                                execformFound = true;
                            }
                        }
                        if (!execformFound) {
                            throw new IOException(
                                "Expected a PostScript line in the form: <form> execform");
                        }
                        gen.writeln("restore");
                    } else {
                        //Do nothing
                    }
                    parser.next();
                }
            }
        }

    }

}
