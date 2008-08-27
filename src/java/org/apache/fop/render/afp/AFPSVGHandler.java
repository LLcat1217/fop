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

package org.apache.fop.render.afp;

// FOP
import java.awt.geom.AffineTransform;
import java.awt.geom.Dimension2D;
import java.io.IOException;
import java.util.Map;

import org.apache.avalon.framework.configuration.Configuration;
import org.apache.batik.bridge.BridgeContext;
import org.apache.batik.bridge.BridgeException;
import org.apache.batik.bridge.GVTBuilder;
import org.apache.batik.dom.AbstractDocument;
import org.apache.batik.dom.svg.SVGDOMImplementation;
import org.apache.batik.gvt.GraphicsNode;
import org.apache.fop.fo.extensions.ExtensionElementMapping;
import org.apache.fop.render.AbstractGenericSVGHandler;
import org.apache.fop.render.Renderer;
import org.apache.fop.render.RendererContext;
import org.apache.fop.render.RendererContextConstants;
import org.apache.fop.svg.SVGEventProducer;
import org.apache.fop.svg.SVGUserAgent;
import org.apache.xmlgraphics.util.QName;
import org.w3c.dom.Document;

/**
 * AFP XML handler for SVG. Uses Apache Batik for SVG processing.
 * This handler handles XML for foreign objects and delegates to AFPGraphics2DAdapter.
 * @see AFPGraphics2DAdapter
 */
public class AFPSVGHandler extends AbstractGenericSVGHandler {

    /** foreign attribute reader */
    private final AFPForeignAttributeReader foreignAttributeReader
        = new AFPForeignAttributeReader();

    /** {@inheritDoc} */
    public void handleXML(RendererContext context,
                Document doc, String ns) throws Exception {
        if (SVGDOMImplementation.SVG_NAMESPACE_URI.equals(ns)) {
            renderSVGDocument(context, doc);
        }
    }

    /**
     * Get the AFP information from the render context.
     *
     * @param context the renderer context
     * @return the AFP information retrieved from the context
     */
    public static AFPInfo getAFPInfo(RendererContext context) {
        AFPInfo afpi = new AFPInfo();
        afpi.setWidth(((Integer)context.getProperty(WIDTH)).intValue());
        afpi.setHeight(((Integer)context.getProperty(HEIGHT)).intValue());
        afpi.setX(((Integer)context.getProperty(XPOS)).intValue());
        afpi.setY(((Integer)context.getProperty(YPOS)).intValue());
        afpi.setHandlerConfiguration((Configuration)context.getProperty(HANDLER_CONFIGURATION));
        afpi.setFontInfo((org.apache.fop.fonts.FontInfo)context.getProperty(
                AFPRendererContextConstants.AFP_FONT_INFO));
        afpi.setState((AFPState)context.getProperty(
                AFPRendererContextConstants.AFP_STATE));
        afpi.setResourceManager(((AFPResourceManager)context.getProperty(
                AFPRendererContextConstants.AFP_RESOURCE_MANAGER)));

        Map foreign = (Map)context.getProperty(RendererContextConstants.FOREIGN_ATTRIBUTES);
        QName qName = new QName(ExtensionElementMapping.URI, null, "conversion-mode");
        if (foreign != null
                && "bitmap".equalsIgnoreCase((String)foreign.get(qName))) {
            afpi.setPaintAsBitmap(true);
        }
        return afpi;
    }

    /**
     * Render the SVG document.
     *
     * @param context the renderer context
     * @param doc the SVG document
     * @throws IOException In case of an I/O error while painting the image
     */
    protected void renderSVGDocument(final RendererContext context,
            final Document doc) throws IOException {

        AFPRenderer renderer = (AFPRenderer)context.getRenderer();
        AFPInfo afpInfo = getAFPInfo(context);

        // fallback paint as bitmap
        if (afpInfo.paintAsBitmap()) {
            try {
                super.renderSVGDocument(context, doc);
            } catch (IOException ioe) {
                SVGEventProducer eventProducer = SVGEventProducer.Provider.get(
                        context.getUserAgent().getEventBroadcaster());
                eventProducer.svgRenderingError(this, ioe, getDocumentURI(doc));
            }
            return;
        }

        String uri = ((AbstractDocument)doc).getDocumentURI();
        AFPState state = (AFPState)renderer.getState();
        state.setImageUri(uri);

        // set the data object parameters
        AFPObjectAreaInfo objectAreaInfo = new AFPObjectAreaInfo();

        AFPUnitConverter unitConv = state.getUnitConverter();

//        RendererContextWrapper rctx = RendererContext.wrapRendererContext(context);
//        int currx = rctx.getCurrentXPosition();
//        int curry = rctx.getCurrentYPosition();
//        int afpx = Math.round(unitConv.mpt2units(currx));
//        int afpy = Math.round(unitConv.mpt2units(curry));
//        objectAreaInfo.setOffsetX(afpx);
//        objectAreaInfo.setOffsetY(afpy);

        AffineTransform at = state.getData().getTransform();
        float transX = (float)at.getTranslateX();
        float transY = (float)at.getTranslateY();
//        int afpx = Math.round(unitConv.mpt2units(currx));
//        objectAreaInfo.setX(afpx);
//        int afpy = Math.round(unitConv.mpt2units(curry));
//        objectAreaInfo.setY(afpy);
//        objectAreaInfo.setX(coords[0]);
//        objectAreaInfo.setY(coords[1]);
        objectAreaInfo.setX(Math.round(transX));
        objectAreaInfo.setY(Math.round(transY));

//        AffineTransform at = currentState.getData().getTransform();
//        int x = (int)Math.round(at.getTranslateX());
//        objectAreaInfo.setX(x);
//
//        int y = (int)Math.round(at.getTranslateY());
//        objectAreaInfo.setY(y);

        int resolution = afpInfo.getResolution();
        objectAreaInfo.setWidthRes(resolution);
        objectAreaInfo.setHeightRes(resolution);

        int width = Math.round(unitConv.mpt2units(afpInfo.getWidth()));
        objectAreaInfo.setWidth(width);

        int height = Math.round(unitConv.mpt2units(afpInfo.getHeight()));
        objectAreaInfo.setHeight(height);

        AFPDataObjectInfo dataObjectInfo = new AFPGraphicsObjectInfo();
        dataObjectInfo.setUri(uri);

        // Configure Graphics2D implementation
        final boolean textAsShapes = false;
        AFPGraphics2D graphics = new AFPGraphics2D(textAsShapes);
        graphics.setGraphicContext(new org.apache.xmlgraphics.java2d.GraphicContext());
        graphics.setAFPInfo(afpInfo);

        // Configure GraphicsObjectPainter with the Graphics2D implementation
        AFPGraphicsObjectPainter painter = new AFPGraphicsObjectPainter(graphics);
        ((AFPGraphicsObjectInfo)dataObjectInfo).setPainter(painter);

        boolean strokeText = false;
        Configuration cfg = afpInfo.getHandlerConfiguration();
        if (cfg != null) {
            strokeText = cfg.getChild("stroke-text", true).getValueAsBoolean(strokeText);
        }
        SVGUserAgent svgUserAgent
            = new SVGUserAgent(context.getUserAgent(), new AffineTransform());

        BridgeContext ctx = new BridgeContext(svgUserAgent);
        AFPTextHandler afpTextHandler = null;

        //Controls whether text painted by Batik is generated using text or path operations
        if (!strokeText) {
            afpTextHandler = new AFPTextHandler(graphics);
            graphics.setCustomTextHandler(afpTextHandler);
            AFPTextPainter textPainter = new AFPTextPainter(afpTextHandler);
            ctx.setTextPainter(textPainter);
            AFPTextElementBridge tBridge = new AFPTextElementBridge(textPainter);
            ctx.putBridge(tBridge);
        }

        Map/*<QName, String>*/ foreignAttributes
            = (Map/*<QName, String>*/)context.getProperty(
                RendererContextConstants.FOREIGN_ATTRIBUTES);
        AFPResourceInfo resourceInfo = foreignAttributeReader.getResourceInfo(foreignAttributes);
        dataObjectInfo.setResourceInfo(resourceInfo);

        // Build the SVG DOM and provide the painter with it
        GraphicsNode root;
        GVTBuilder builder = new GVTBuilder();
        try {
            root = builder.build(ctx, doc);
            painter.setGraphicsNode(root);
        } catch (BridgeException e) {
            SVGEventProducer eventProducer = SVGEventProducer.Provider.get(
                    context.getUserAgent().getEventBroadcaster());
            eventProducer.svgNotBuilt(this, e, uri);
            return;
        }

        // convert to afp inches
        Dimension2D dim = ctx.getDocumentSize();
        double w = dim.getWidth() * 1000f;
        double h = dim.getHeight() * 1000f;
        double wx = (afpInfo.getWidth() / w);
        double hx = (afpInfo.getHeight() / h);
        double scaleX = unitConv.pt2units((float)wx);
        double scaleY = unitConv.pt2units((float)hx);
        double xOffset = unitConv.mpt2units(afpInfo.getX());
        double yOffset = unitConv.mpt2units(afpInfo.getHeight());

        // Transformation matrix that establishes the local coordinate system
        // for the SVG graphic in relation to the current coordinate system
        // (note: y axis is inverted)
        AffineTransform trans = new AffineTransform(scaleX, 0, 0, -scaleY, xOffset, yOffset);
        graphics.setTransform(trans);

        // Set the object area info
        dataObjectInfo.setObjectAreaInfo(objectAreaInfo);

        AFPResourceManager resourceManager = afpInfo.getAFPResourceManager();

        // Create the graphics object
        resourceManager.createObject(dataObjectInfo);
    }

    /** {@inheritDoc} */
    public boolean supportsRenderer(Renderer renderer) {
        return (renderer instanceof AFPRenderer);
    }

    /** {@inheritDoc} */
    protected void updateRendererContext(RendererContext context) {
        //Work around a problem in Batik: Gradients cannot be done in ColorSpace.CS_GRAY
        context.setProperty(AFPRendererContextConstants.AFP_GRAYSCALE, Boolean.FALSE);
    }

}
