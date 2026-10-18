package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.Townstead;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Planner overlays are drawn on top of everything (like a map annotation), so tree canopies and hills never hide
 * the land you're planning. Vanilla lines with the depth test turned off.
 */
public final class PlannerRenderTypes {
    public static final RenderPipeline OVERLAY_LINES_PIPELINE = RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withLocation(Townstead.id("pipeline/planner_overlay_lines"))
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .build();

    public static final RenderType OVERLAY_LINES = RenderType.create("townstead_planner_overlay_lines",
            RenderSetup.builder(OVERLAY_LINES_PIPELINE).setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                    .createRenderSetup());

    private PlannerRenderTypes() {}
}
