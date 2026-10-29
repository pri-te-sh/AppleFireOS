package com.applefireos.home;

/**
 * The design tokens of the whole home screen: every colour, size, corner, gap and focus lift
 * comes from here (motion lives in Motion). Screens never pick their own values; they use Kit's
 * components, which are built from these. tools/design-lint.sh enforces it at build time.
 */
final class Theme {
    private Theme() {}

    // ---- colour roles --------------------------------------------------------------------

    /** Text and glyphs on dark surfaces: primary, secondary, tertiary, disabled. */
    static final int LABEL = 0xFFFFFFFF, LABEL_2 = 0xB8FFFFFF, LABEL_3 = 0x80FFFFFF, LABEL_4 = 0x4DFFFFFF;
    /** Text and glyphs on the white focus platter. */
    static final int ON_FOCUS = 0xFF111114, ON_FOCUS_2 = 0x99000000;
    /** The focus platter: tvOS's bright highlight behind the focused row or button. */
    static final int FOCUS = 0xF7FFFFFF;
    /** Content cards are plain fills with a hairline (never glass: glass is for floating controls). */
    static final int CARD = 0x12FFFFFF, CARD_EDGE = 0x17FFFFFF;
    /** Idle controls, tracks and wells. */
    static final int FILL = 0x24FFFFFF, FILL_2 = 0x14FFFFFF;
    static final int SEPARATOR = 0x1FFFFFFF;
    /** Behind full-screen pages: near-black, never tinted by the artwork. */
    static final int PAGE = 0xFF0B0B0F, PAGE_DIM = 0xE60B0B0F;
    /** The home screen's own background (under the aurora), and true black around it. */
    static final int STAGE = 0xFF05060A, BLACK = 0xFF000000;
    /** Behind floating panels. */
    static final int SCRIM = 0x66000000, SCRIM_LIGHT = 0x40000000, SCRIM_FAINT = 0x0D000000;
    /** Glass tints: over the frosted backdrop, light for small controls, darker under text. */
    static final int GLASS = 0x6E1C1C24, GLASS_THICK = 0xA316161E, GLASS_FOCUS = 0x66FFFFFF;
    /** Small controls over artwork (the "‹ Home" pill, shelf arrows). */
    static final int CHROME = 0x47000000;
    static final int SHADOW = 0x8C000000;
    /** The profile avatar's gradient. */
    static final int AVATAR_TOP = 0xFF8E9BFF, AVATAR_BOTTOM = 0xFF5B5FD6;

    static final int ACCENT = 0xFF0A84FF;
    static final int GOOD = 0xFF30D158, WARNING = 0xFFFF9F0A, CRITICAL = 0xFFFF453A;
    /** Data series for charts, in fixed order; anything past them folds into OTHER. */
    static final int[] SERIES = { 0xFF3987E5, 0xFFD95926, 0xFF199E70 };
    static final int OTHER = 0xFF5D5D63;

    /** Icon-tile colours (Apple's system palette), so every coloured icon comes from one set. */
    static final int BLUE = 0xFF0A84FF, INDIGO = 0xFF5E5CE6, PURPLE = 0xFFBF5AF2, PINK = 0xFFFF375F,
            RED = 0xFFFF453A, ORANGE = 0xFFFF9F0A, YELLOW = 0xFFFFD60A, GREEN = 0xFF30D158,
            TEAL = 0xFF30B0C7, CYAN = 0xFF64D2FF, GRAY = 0xFF8E8E93, GRAPHITE = 0xFF48484A;

    // ---- type scale (stage px) -------------------------------------------------------------

    enum Type {
        /** The Top Shelf's title. */
        DISPLAY(84, 2, -0.018f),
        PAGE_TITLE(48, 2, -0.01f),
        TITLE(38, 2, 0),
        HEADLINE(30, 1, 0),
        BODY(28, 0, 0),
        CALLOUT(26, 1, 0),
        SUBHEAD(24, 0, 0),
        FOOTNOTE(22, 0, 0),
        CAPTION(20, 0, 0),
        /** Upper-case group labels ("USING MEMORY", "RECENT"). */
        SECTION(22, 1, 0.08f);

        final float size, tracking;
        /** 0 regular, 1 medium, 2 bold. */
        final int weight;

        Type(float size, int weight, float tracking) {
            this.size = size;
            this.weight = weight;
            this.tracking = tracking;
        }
    }

    // ---- shape and space ---------------------------------------------------------------------

    /** Corners: small (wells, art), medium (rows, tiles in panels), large (cards, app tiles), panel. */
    static final float R_SMALL = 12, R_MEDIUM = 20, R_LARGE = 30, R_PANEL = 46;

    /** Spacing on an 8-ish grid. */
    static final int S1 = 8, S2 = 14, S3 = 22, S4 = 34, S5 = 48;
    /** Page margins: the same as the home screen's grid. */
    static final int PAGE_X = 90, PAGE_TOP = 52, PAGE_CONTENT_TOP = 150;

    /** Line spacing for paragraphs (descriptions, overviews). */
    static final float LINE_SPACING = 1.38f;

    /** Row heights. */
    static final int ROW = 76, ROW_COMPACT = 64, BUTTON = 72;

    // ---- focus -----------------------------------------------------------------------------

    /** How much a focused thing grows: rows, controls, tiles. */
    static final float LIFT_ROW = 1.03f, LIFT_CONTROL = 1.06f, LIFT_TILE = 1.12f;
}
