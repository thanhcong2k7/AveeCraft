package com.daaw.avee.comp.Visualizer.Elements;

import android.content.Context;
import android.graphics.RectF;
import android.graphics.Typeface;
import com.daaw.avee.Common.Action3;
import com.daaw.avee.Common.Utils;
import com.daaw.avee.Common.Vec2f;
import com.daaw.avee.Common.Vec2i;
import com.daaw.avee.Common.Vec3f;
import com.daaw.avee.PlayerCore;
import com.daaw.avee.comp.Common.MeasureDefs;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.Elements.Base.Element;
import com.daaw.avee.comp.Visualizer.Graphic.CharSet;
import com.daaw.avee.comp.Visualizer.Graphic.GraphicsUtils;
import com.daaw.avee.comp.Visualizer.Graphic.RenderPassData;
import com.daaw.avee.comp.Visualizer.Graphic.RenderState;
import com.daaw.avee.comp.Visualizer.Graphic.SpriteFont;
import com.daaw.avee.comp.Visualizer.Graphic.VMatrix;
import com.daaw.avee.comp.Visualizer.Graphic.VShaderProgram;
import com.daaw.avee.comp.Visualizer.IDependencyResourceCounter;
import mdesl.graphics.glutils.FrameBuffer;
/* loaded from: classes.dex */
public class TextElement extends Element {
    public static final String[] fontTypefaces = {"internal_0:2131230895", "internal_1:2131230896", "internal_2:2131230897", "internal_3:2131230898"};
    public static final String typeName = "Text";
    protected int color1;
    private String customFontPath;
    protected SpriteFont font1;
    private int fontSize;
    protected int maxfontSizePixelsLimit;
    Action3<RenderState, VShaderProgram, RenderPassData> shaderOnBindAction;
    private String text;
    public final VMatrix vpMatTmp;

    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public String getElementTypeName() {
        return typeName;
    }

    public void onRenderTextElement(RenderState renderState, RectF rectF) {
    }

    public TextElement() {
        super(0, 0.5f, 0.5f);
        this.text = MeasureDefs.textArtistOrTitle;
        this.fontSize = 30;
        this.color1 = -1;
        this.customFontPath = "";
        this.maxfontSizePixelsLimit = 500;
        this.vpMatTmp = new VMatrix();
        this.shaderOnBindAction = new Action3<RenderState, VShaderProgram, RenderPassData>() { // from class: com.daaw.avee.comp.Visualizer.Elements.TextElement.1
            @Override // com.daaw.avee.Common.Action3
            public void onInvoke(RenderState renderState, VShaderProgram vShaderProgram, RenderPassData renderPassData) {
                vShaderProgram.setUniformMatrix("u_projView", false, TextElement.this.vpMatTmp.getObj());
            }
        };
    }

    public void setText(String str) {
        this.text = str;
    }

    public void setFontSize(int i) {
        if (this.fontSize == i) {
            return;
        }
        this.fontSize = Math.min(500, i);
        markNeedReCreateGLResources();
    }

    public void setColor(int i) {
        this.color1 = i;
    }

    public void setColorAlpha(int i) {
        this.color1 = GraphicsUtils.setIntColorAlpha(this.color1, i);
    }

    public String getCustomFontPath() {
        return this.customFontPath;
    }

    public void setCustomFontPath(String str) {
        if (Utils.compareNullStrings(this.customFontPath, str)) {
            return;
        }
        this.customFontPath = str;
        markNeedReCreateGLResources();
    }

    public void limitColor(float f, float f2) {
        float[] fArr = {1.0f, 1.0f, 1.0f, 1.0f};
        GraphicsUtils.intColorToHlsa360(fArr, this.color1);
        fArr[2] = Math.max(f2, fArr[2]);
        fArr[3] = Math.max(f, fArr[3]);
        setColor(GraphicsUtils.hlsa360ColorToInt(fArr));
    }

    public void limitFontSize(int i) {
        this.fontSize = Math.max(i, this.fontSize);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public void onApplyCustomization(CustomPropertiesList customPropertiesList) {
        super.onApplyCustomization(customPropertiesList);
        super.onApplyCustomizationAdditional(customPropertiesList);
        setText(customPropertiesList.getPropertyString("text", this.text));
        setColor(customPropertiesList.getPropertyInt("color", -1));
        setFontSize(customPropertiesList.getPropertyInt("fontSize", 30));
        setCustomFontPath(customPropertiesList.getPropertyString("typeFace", ""));
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void onApplyCustomization0(CustomPropertiesList customPropertiesList) {
        setCustomFontPath(customPropertiesList.getPropertyString("typeFace", getCustomFontPath()));
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public void onReadCustomization(CustomPropertiesList customPropertiesList, IDependencyResourceCounter iDependencyResourceCounter) {
        super.onReadCustomization(customPropertiesList, iDependencyResourceCounter);
        super.onReadCustomizationAdditional(customPropertiesList);
        customPropertiesList.setCustomizationName(typeName);
        customPropertiesList.putPropertyStringAsTxtPr("text", this.text, "appearance");
        customPropertiesList.putPropertyIntAsCRGBA("color", this.color1, "appearance");
        customPropertiesList.putPropertyIntAsRange("fontSize", this.fontSize, "appearance", 8, 100);
        customPropertiesList.putPropertyStringAsAssetPr("typeFace", getCustomFontPath(), "appearance", fontTypefaces);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public void onReadCustomization0(CustomPropertiesList customPropertiesList) {
        customPropertiesList.putPropertyStringAsAssetPr("typeFace", getCustomFontPath(), "appearance", fontTypefaces);
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public void onDestroyGLResources(RenderState renderState) {
        super.onDestroyGLResources(renderState);
        SpriteFont spriteFont = this.font1;
        if (spriteFont != null) {
            spriteFont.dispose();
        }
        this.font1 = null;
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public boolean onCreateGLResources(RenderState renderState) {
        int min = Math.min((int) renderState.getRes().getMeter().measureScreenSpace(this.fontSize * 0.14f * 0.01f), this.maxfontSizePixelsLimit);
        Typeface typeface = Typeface.DEFAULT;
        String fontKey = this.customFontPath != null ? this.customFontPath : "";
        if (fontKey.startsWith("internal_")) {
            int colon = fontKey.indexOf(':');
            if (colon > 0) {
                fontKey = fontKey.substring(0, colon);
            }
        }
        if ("".equals(fontKey)) {
            typeface = Typeface.DEFAULT;
        } else if ("default_bold".equals(fontKey)) {
            typeface = Typeface.DEFAULT_BOLD;
        } else {
            Context appContext = PlayerCore.s().getAppContext();
            try {
                if ("internal_0".equals(fontKey) && appContext != null) {
                    typeface = Typeface.createFromAsset(appContext.getAssets(), "fonts/greatvibes-regular.otf");
                } else if ("internal_1".equals(fontKey) && appContext != null) {
                    typeface = Typeface.createFromAsset(appContext.getAssets(), "fonts/notomono-regular.ttf");
                } else if ("internal_2".equals(fontKey) && appContext != null) {
                    typeface = Typeface.createFromAsset(appContext.getAssets(), "fonts/orbitron-medium.otf");
                } else if ("internal_3".equals(fontKey) && appContext != null) {
                    typeface = Typeface.createFromAsset(appContext.getAssets(), "fonts/orkney-light.otf");
                } else {
                    java.io.File fontFile = com.daaw.avee.comp.AlbumArt.AlbumArtCore.resolveLocalOrFile(fontKey);
                    if (fontFile != null && fontFile.isFile()) {
                        typeface = Typeface.createFromFile(fontFile);
                    }
                }
            } catch (Exception unused) {
                typeface = Typeface.DEFAULT;
            }
        }
        SpriteFont spriteFont = this.font1;
        if (spriteFont != null) {
            spriteFont.dispose();
        }
        this.font1 = new SpriteFont(typeface, min, CharSet.createAscii32to126());
        return super.onCreateGLResources(renderState);
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public void onRender(RenderState renderState, FrameBuffer frameBuffer) {
        super.onRender(renderState, frameBuffer);
        SpriteFont spriteFont = this.font1;
        if (spriteFont == null || !spriteFont.isValid()) {
            return;
        }
        String measureText = renderState.res.meter.measureText(this.text);
        new Vec2i(0, 0);
        Vec2i measureText2 = renderState.res.getFontRenderer().measureText(this.font1, measureText);
        RectF measureDrawRect = measureDrawRect(renderState.res.meter, measureText2);
        float measureDrawRot = measureDrawRot(renderState.getRes().getMeter());
        Vec2f drawOffset = getDrawOffset(measureDrawRect);
        createRotationAndVpMat(renderState, this.vpMatTmp, measureDrawRect.left + (measureText2.x * 0.5f), measureDrawRect.top - (measureText2.y * 0.5f), measureDrawRot);
        renderState.res.getFontRenderer().drawText(renderState, this.font1, new Vec3f(measureDrawRect.left + drawOffset.x, measureDrawRect.top + drawOffset.y, 0.0f), measureText, this.color1, 0, false, 0, 0, 0, 0, new RenderPassData(getBlendMode(), this.font1.getEntryTexture(), renderState.res.getBufferRenderer_atlasBufferVPMat(), this.shaderOnBindAction));
        onRenderTextElement(renderState, measureDrawRect);
    }

    public Vec2f getDrawOffset(RectF rectF) {
        return new Vec2f(0.0f, 0.0f);
    }
}
