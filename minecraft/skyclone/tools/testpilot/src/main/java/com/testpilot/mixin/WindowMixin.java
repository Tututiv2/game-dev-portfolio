package com.testpilot.mixin;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import com.testpilot.NoActivate;
import com.testpilot.TestPilot;
import com.testpilot.WindowPlacement;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * The no-focus window. This is the requirement everything else depends on.
 *
 * <ol>
 *   <li>Before {@code glfwCreateWindow}: VISIBLE=false, FOCUSED=false,
 *       FOCUS_ON_SHOW=false. GLFW only runs its focus routine (BringWindowToTop +
 *       SetForegroundWindow) when FOCUSED is set on a visible create, or when
 *       FOCUS_ON_SHOW is set on a show; with both off it shows with SW_SHOWNA,
 *       which never activates.</li>
 *   <li>The DisplayData is forced to windowed 1280x720 - GLFW always focuses a
 *       fullscreen window, whatever the hints say.</li>
 *   <li>Before {@code setMode()} positions the window, its windowed x/y are moved
 *       onto the non-primary monitor (when there is one).</li>
 *   <li>At the end of the constructor the still-hidden window is shown, without
 *       activation.</li>
 * </ol>
 */
@Mixin(Window.class)
public abstract class WindowMixin {

    @Shadow @Final private long handle;
    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow private boolean focused;

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static DisplayData testpilot$windowed(DisplayData data) {
        if (!TestPilot.ACTIVE) {
            return data;
        }
        int[] wh = WindowPlacement.size();
        return new DisplayData(wh[0], wh[1], OptionalInt.empty(), OptionalInt.empty(), false);
    }

    @Inject(method = "createGlfwWindow",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"))
    private static void testpilot$hints(int width, int height, String title, long monitor, GpuBackend backend,
                                        CallbackInfoReturnable<Long> cir) {
        if (!TestPilot.ACTIVE) {
            return;
        }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        TestPilot.LOG.info("[testpilot] window hints: VISIBLE=0 FOCUSED=0 FOCUS_ON_SHOW=0 (monitor={})", monitor);
    }

    @Inject(method = "<init>",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;setMode()V"))
    private void testpilot$place(CallbackInfo ci) {
        if (!TestPilot.ACTIVE) {
            return;
        }
        int[] pos = WindowPlacement.position(this.windowedWidth, this.windowedHeight);
        if (pos != null) {
            this.windowedX = pos[0];
            this.x = pos[0];
            this.windowedY = pos[1];
            this.y = pos[1];
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void testpilot$show(CallbackInfo ci) {
        if (!TestPilot.ACTIVE) {
            return;
        }
        // FOCUS_ON_SHOW is also a per-window attribute; pin it so that any later
        // glfwShowWindow, from anywhere, cannot activate the window either.
        GLFW.glfwSetWindowAttrib(this.handle, GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        NoActivate.apply(this.handle);
        GLFW.glfwShowWindow(this.handle);
        // Vanilla initialises Window.focused to TRUE and only ever updates it from
        // the focus callback - which never fires for a window that is never
        // focused. Left alone, the game would believe it is the active window for
        // its whole life. Read the truth from GLFW instead.
        this.focused = GLFW.glfwGetWindowAttrib(this.handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        TestPilot.LOG.info("[testpilot] window shown without activation at {},{} size {}x{} (focused={})",
                this.x, this.y, this.width, this.height, this.focused);
    }
}
