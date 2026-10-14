package com.anthropic.claudecode.eclipse.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.internal.Converter;
import org.eclipse.swt.internal.gtk.GTK;
import org.eclipse.swt.internal.gtk.OS;
import org.eclipse.swt.internal.gtk3.GTK3;
import org.eclipse.swt.internal.gtk4.GTK4;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Scrollable;

/**
 * How a terminal canvas presents its scrollbar: persistent beside the canvas or the theme's
 * overlay one painted over it, and, on GTK, standing on the terminal's background rather than on
 * a panel of theme grey. The surface only — the slider stays the theme's, and with it the focus
 * and hover colors every other scrollbar in the workbench shows.
 */
final class TerminalScrollbar {

    /** GTK is the only platform whose scrollbars we can recolor — see {@link Gtk}. */
    private static final boolean ON_GTK = "gtk".equals(SWT.getPlatform());

    private TerminalScrollbar() {}

    /**
     * Presents {@code canvas}'s scrollbar. Safe to call again whenever the preference or the
     * terminal colors change, and it hands the bar back to the theme when {@code persistent}
     * goes false.
     *
     * <p>The mode is set unconditionally: asking for the mode already in use is how SWT itself
     * no-ops, and on Windows and macOS {@code setScrollbarsMode} does nothing at all. An overlay
     * bar keeps the theme's colors, since it floats over the canvas rather than sitting in it.
     */
    static void apply(Scrollable canvas, boolean persistent, RGB background) {
        canvas.setScrollbarsMode(persistent ? SWT.NONE : SWT.SCROLLBAR_OVERLAY);
        if (!ON_GTK) return;   // Gtk reaches into the GTK fragment; never load it elsewhere
        if (persistent) {
            Gtk.paint(canvas, background);
        } else {
            Gtk.reset(canvas);
        }
    }

    /**
     * The GTK half, in a class of its own so that it loads only once {@link #ON_GTK} has said we
     * are on GTK: the {@code org.eclipse.swt.internal} packages it imports live in the GTK
     * fragment and exist nowhere else.
     *
     * <p>SWT has no API for any of this — a {@link ScrollBar} is not a
     * {@link org.eclipse.swt.widgets.Control} and carries no colors of its own — but on GTK it has
     * a widget handle whose CSS nodes include the trough and the slider, so one style provider
     * attached to the bar restyles all of it. A provider is created once per bar and left to the
     * bar's style context; recoloring only reloads its CSS.
     */
    private static final class Gtk {

        /** Widget data keys under which a bar remembers the GtkCssProviders we gave it. */
        private static final String PROVIDER_KEY = "com.anthropic.claudecode.eclipse.adt.cssProvider";
        private static final String CONTAINER_KEY = "com.anthropic.claudecode.eclipse.adt.cssProvider.container";

        /** Sets both of {@code widget}'s scrollbars into {@code background}. */
        private static void paint(Scrollable widget, RGB background) {
            String css = css(background);
            ScrollBar vertical = widget.getVerticalBar();
            style(vertical, css);
            style(widget.getHorizontalBar(), css);
            styleContainer(vertical, background);
        }

        /** Hands the scrollbars back to the theme; bars we never styled are left untouched. */
        private static void reset(Scrollable widget) {
            reset(widget.getVerticalBar());
            reset(widget.getHorizontalBar());
        }

        private static void reset(ScrollBar bar) {
            if (bar == null) return;
            if (bar.getData(PROVIDER_KEY) != null) style(bar, "");
            if (bar.getData(CONTAINER_KEY) != null) {
                style(GTK.gtk_widget_get_parent(bar.handle), bar, CONTAINER_KEY, "");
            }
        }

        /**
         * Closes the gap the scrolled window keeps between the canvas and the bar, and paints what
         * is left of it.
         *
         * <p>GTK3 holds the bar {@code scrollbar-spacing} clear of the client area — 3px unless
         * the theme says otherwise, and nothing but distance between the last column and the
         * slider. GTK4 dropped the gap along with the property, and warns about names it no longer
         * knows, hence the version test. The container is painted here rather than by setting an
         * SWT background because Eclipse's theme engine restyles the composite after we are done
         * with it and has no say over a GTK provider.
         */
        private static void styleContainer(ScrollBar bar, RGB background) {
            if (bar == null) return;
            long scrolledWindow = GTK.gtk_widget_get_parent(bar.handle);
            if (scrolledWindow == 0) return;
            style(scrolledWindow, bar, CONTAINER_KEY, "scrolledwindow {"
                    + " background-color: " + ColorUtils.toHex(background) + ";"
                    + (GTK.GTK4 ? "" : " -GtkScrolledWindow-scrollbar-spacing: 0;")
                    + " }");
        }

        /**
         * The bar, its trough and its border take the terminal background. The slider never does,
         * and the trough is ours only while the pointer is away, so a theme keeps both halves of
         * the feedback a scrollbar gives: its slider colors, and the wash it lays under the
         * pointer — translucent, so it falls on the terminal's color instead of replacing it.
         *
         * <p>Every declaration recolors, none resizes. Pixels a widget gives up belong to nobody
         * and open a bare gap, so borders keep their geometry and lose only their color rather
         * than being dropped. {@code background-image} and {@code box-shadow} cost no space and
         * would cover whatever we set beneath them, so those are cleared outright.
         */
        private static String css(RGB background) {
            String bg = ColorUtils.toHex(background);
            String plain = " background-color: " + bg + "; background-image: none; border-color: "
                    + bg + "; box-shadow: none; }";
            return "scrollbar, scrollbar button {" + plain
                    + "scrollbar:not(:hover) trough {" + plain;
        }

        private static void style(ScrollBar bar, String css) {
            if (bar != null) style(bar.handle, bar, PROVIDER_KEY, css);
        }

        private static void style(long widget, ScrollBar owner, String key, String css) {
            long provider;
            if (owner.getData(key) instanceof Long existing) {
                provider = existing;
            } else {
                provider = GTK.gtk_css_provider_new();
                GTK.gtk_style_context_add_provider(GTK.gtk_widget_get_style_context(widget),
                        provider, GTK.GTK_STYLE_PROVIDER_PRIORITY_APPLICATION);
                OS.g_object_unref(provider); // the style context keeps it alive from here on
                owner.setData(key, provider);
            }
            byte[] data = Converter.wcsToMbcs(css, true);
            if (GTK.GTK4) {
                GTK4.gtk_css_provider_load_from_data(provider, data, -1);
            } else {
                GTK3.gtk_css_provider_load_from_data(provider, data, -1, null);
            }
        }
    }
}
