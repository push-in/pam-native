use std::collections::{BTreeMap, BTreeSet};
use std::fmt;

pub const TREE_MAGIC: [u8; 4] = *b"PNT1";
pub const PATCH_MAGIC: [u8; 4] = *b"PNP1";
pub const BATCH_MAGIC: [u8; 4] = *b"PNB1";
pub const PROTOCOL_VERSION: u16 = 1;
pub const MAX_FRAME_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_NODES: usize = 100_000;
pub const MAX_TREE_DEPTH: usize = 512;
pub const MAX_PROPERTIES_PER_NODE: usize = 256;
pub const MAX_VALUE_BYTES: usize = 1024 * 1024;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum NodeKind {
    Screen = 1,
    Column = 2,
    Row = 3,
    Text = 4,
    Button = 5,
    Input = 6,
    Image = 7,
    Scroll = 8,
    List = 9,
    Spacer = 10,
    View = 11,
    Pressable = 12,
    ActivityIndicator = 13,
    Switch = 14,
    Modal = 15,
    ImageBackground = 16,
    KeyboardAvoidingView = 17,
    SectionList = 18,
    RefreshControl = 19,
    StatusBar = 20,
    SafeAreaView = 21,
    DrawerLayout = 22,
    InputAccessoryView = 23,
    CustomView = 24,
    NavigationHost = 25,
    VirtualList = 26,
    WebView = 27,
    Media = 28,
    DrawingCanvas = 29,
}

impl TryFrom<u8> for NodeKind {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Screen),
            2 => Ok(Self::Column),
            3 => Ok(Self::Row),
            4 => Ok(Self::Text),
            5 => Ok(Self::Button),
            6 => Ok(Self::Input),
            7 => Ok(Self::Image),
            8 => Ok(Self::Scroll),
            9 => Ok(Self::List),
            10 => Ok(Self::Spacer),
            11 => Ok(Self::View),
            12 => Ok(Self::Pressable),
            13 => Ok(Self::ActivityIndicator),
            14 => Ok(Self::Switch),
            15 => Ok(Self::Modal),
            16 => Ok(Self::ImageBackground),
            17 => Ok(Self::KeyboardAvoidingView),
            18 => Ok(Self::SectionList),
            19 => Ok(Self::RefreshControl),
            20 => Ok(Self::StatusBar),
            21 => Ok(Self::SafeAreaView),
            22 => Ok(Self::DrawerLayout),
            23 => Ok(Self::InputAccessoryView),
            24 => Ok(Self::CustomView),
            25 => Ok(Self::NavigationHost),
            26 => Ok(Self::VirtualList),
            27 => Ok(Self::WebView),
            28 => Ok(Self::Media),
            29 => Ok(Self::DrawingCanvas),
            other => Err(ProtocolError::UnknownNodeKind(other)),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
#[repr(u16)]
pub enum PropKey {
    Text = 1,
    Value = 2,
    Placeholder = 3,
    Source = 4,
    Width = 5,
    Height = 6,
    FlexGrow = 7,
    Padding = 8,
    Gap = 9,
    BackgroundColor = 10,
    TextColor = 11,
    FontSize = 12,
    Enabled = 13,
    OnPress = 14,
    OnChange = 15,
    Items = 16,
    AccessibilityLabel = 17,
    TestId = 18,
    OnLongPress = 19,
    OnFocus = 20,
    OnBlur = 21,
    OnSubmit = 22,
    OnScroll = 23,
    OnRefresh = 24,
    OnToggle = 25,
    Margin = 26,
    MarginHorizontal = 27,
    MarginVertical = 28,
    PaddingHorizontal = 29,
    PaddingVertical = 30,
    MinWidth = 31,
    MinHeight = 32,
    MaxWidth = 33,
    MaxHeight = 34,
    BorderRadius = 35,
    BorderWidth = 36,
    BorderColor = 37,
    Opacity = 38,
    AlignItems = 39,
    AlignSelf = 40,
    JustifyContent = 41,
    TextAlign = 42,
    FontWeight = 43,
    NumberOfLines = 44,
    Multiline = 45,
    Secure = 46,
    KeyboardType = 47,
    AutoComplete = 48,
    InputDebounceMs = 49,
    InputSyncMode = 50,
    Checked = 51,
    Loading = 52,
    ProgressColor = 53,
    ImageFit = 54,
    TintColor = 55,
    Elevation = 56,
    Visible = 57,
    ModalPresentation = 58,
    StatusBarColor = 59,
    StatusBarStyle = 60,
    StatusBarHidden = 61,
    KeyboardBehavior = 62,
    Refreshing = 63,
    ScrollEnabled = 64,
    ShowsScrollIndicator = 65,
    Selected = 66,
    RippleColor = 67,
    PressOpacity = 68,
    Collapsable = 69,
    AccessibilityRole = 70,
    AccessibilityHint = 71,
    TranslationX = 72,
    TranslationY = 73,
    ScaleX = 74,
    ScaleY = 75,
    Rotation = 76,
    AnimationDurationMs = 77,
    AnimationEasing = 78,
    AnimateChanges = 79,
    SectionItems = 80,
    ListRowHeight = 81,
    ListPrefetch = 82,
    OnEndReached = 83,
    EndReachedThreshold = 84,
    DrawerOpen = 85,
    DrawerPosition = 86,
    OnDrawerOpen = 87,
    OnDrawerClose = 88,
    LetterSpacing = 89,
    LineHeight = 90,
    PlaceholderColor = 91,
    SelectionColor = 92,
    MaxLength = 93,
    AutoFocus = 94,
    ReturnKeyType = 95,
    HitSlop = 96,
    ZIndex = 97,
    Overflow = 98,
    HostName = 99,
    HostProperties = 100,
    OnNativeEvent = 101,
    FlexDirection = 102,
    FlexShrink = 103,
    PaddingLeft = 104,
    PaddingTop = 105,
    PaddingRight = 106,
    PaddingBottom = 107,
    MarginLeft = 108,
    MarginTop = 109,
    MarginRight = 110,
    MarginBottom = 111,
    PositionType = 112,
    Left = 113,
    Top = 114,
    Right = 115,
    Bottom = 116,
    AspectRatio = 117,
    BorderTopLeftRadius = 118,
    BorderTopRightRadius = 119,
    BorderBottomRightRadius = 120,
    BorderBottomLeftRadius = 121,
    BorderLeftWidth = 122,
    BorderTopWidth = 123,
    BorderRightWidth = 124,
    BorderBottomWidth = 125,
    TextDecoration = 126,
    TextTransform = 127,
    FontStyle = 128,
    WidthPercent = 129,
    HeightPercent = 130,
    MaxWidthPercent = 131,
    MaxHeightPercent = 132,
    PointerEvents = 133,
    SafeAreaBottom = 134,
    BlurRadius = 135,
    FontFamily = 136,
    MarginLeftAuto = 137,
    TranslationXPercent = 138,
    AnimationKind = 139,
    Accessible = 140,
    AccessibilityLiveRegion = 141,
    AccessibilityImportance = 142,
    AccessibilityExpanded = 143,
    AccessibilityBusy = 144,
    AccessibilityCheckedState = 145,
    AccessibilityValueMin = 146,
    AccessibilityValueMax = 147,
    AccessibilityValueNow = 148,
    AccessibilityValueText = 149,
    SafeAreaTop = 150,
    SafeAreaRight = 151,
    SafeAreaBottomEdge = 152,
    SafeAreaLeft = 153,
    SafeAreaMode = 154,
    KeyboardVerticalOffset = 155,
    KeyboardAvoidingEnabled = 156,
    RefreshColors = 157,
    RefreshProgressBackgroundColor = 158,
    RefreshProgressViewOffset = 159,
    RefreshIndicatorSize = 160,
    TextSelectable = 161,
    TextEllipsizeMode = 162,
    TextAllowFontScaling = 163,
    TextMaxFontSizeMultiplier = 164,
    TextAdjustsFontSizeToFit = 165,
    TextMinimumFontScale = 166,
    TextBreakStrategy = 167,
    TextHyphenationFrequency = 168,
    TextDataDetectorType = 169,
    StatusBarAnimated = 170,
    StatusBarTranslucent = 171,
    ListHorizontal = 172,
    ListNumColumns = 173,
    ListInverted = 174,
    ListInitialScrollIndex = 175,
    ListRemoveClippedSubviews = 176,
    ScrollHorizontal = 177,
    ScrollContentOffsetX = 178,
    ScrollContentOffsetY = 179,
    ScrollFillViewport = 180,
    ScrollOverScrollMode = 181,
    ScrollNestedEnabled = 182,
    ScrollFadingEdgeLength = 183,
    ScrollPersistentScrollbar = 184,
    ScrollPagingEnabled = 185,
    ScrollSnapInterval = 186,
    ScrollDecelerationRate = 187,
    ScrollKeyboardDismissMode = 188,
    ActivityAnimating = 189,
    ActivityHidesWhenStopped = 190,
    ActivitySize = 191,
    SwitchTrackColorFalse = 192,
    SwitchTrackColorTrue = 193,
    SwitchThumbColor = 194,
    ImageDefaultSource = 195,
    ImageLoadingIndicatorSource = 196,
    ImageFadeDurationMs = 197,
    ImageResizeMethod = 198,
    ImageResizeMultiplier = 199,
    ImageProgressiveRenderingEnabled = 200,
    ImageCachePolicy = 201,
    ImageOverlayColor = 202,
    ImageSourceSet = 203,
    ImageRequestHeaders = 204,
    OnImageLoadStart = 205,
    OnImageProgress = 206,
    OnImageLoad = 207,
    OnImageError = 208,
    OnImageLoadEnd = 209,
    InputEditable = 210,
    InputAutoCorrect = 211,
    InputAutoCapitalize = 212,
    InputCaretHidden = 213,
    InputContextMenuHidden = 214,
    InputCursorColor = 215,
    InputDisableFullscreenUi = 216,
    InputAutofillImportance = 217,
    InputMode = 218,
    InputMinLines = 219,
    InputSelectTextOnFocus = 220,
    InputSelectionStart = 221,
    InputSelectionEnd = 222,
    InputShowSoftInputOnFocus = 223,
    InputSubmitBehavior = 224,
    InputTextAlignVertical = 225,
    InputReturnKeyLabel = 226,
    InputScrollEnabled = 227,
    InputUnderlineColor = 228,
    OnInputEndEditing = 229,
    OnInputSelectionChange = 230,
    OnInputContentSizeChange = 231,
    OnInputKeyPress = 232,
    HitSlopLeft = 233,
    HitSlopTop = 234,
    HitSlopRight = 235,
    HitSlopBottom = 236,
    PressRetentionLeft = 237,
    PressRetentionTop = 238,
    PressRetentionRight = 239,
    PressRetentionBottom = 240,
    PressDelayLongMs = 241,
    PressDelayInMs = 242,
    PressDelayOutMs = 243,
    PressAndroidDisableSound = 244,
    RippleBorderless = 245,
    RippleRadius = 246,
    RippleForeground = 247,
    RippleAlpha = 248,
    OnPressIn = 249,
    OnPressOut = 250,
    OnPressMove = 251,
    ModalAnimationType = 252,
    ModalBackdropColor = 253,
    ModalTransparent = 254,
    ModalHardwareAccelerated = 255,
    ModalNavigationBarTranslucent = 256,
    ModalStatusBarTranslucent = 257,
    ModalAllowSwipeDismissal = 258,
    OnModalRequestClose = 259,
    OnModalShow = 260,
    OnModalDismiss = 261,
    OnModalOrientationChange = 262,
    GridColumns = 263,
    GridSpan = 264,
    GridSpanSm = 265,
    GridSpanMd = 266,
    GridSpanLg = 267,
    GridSpanXl = 268,
    GridOffset = 269,
    GridOffsetSm = 270,
    GridOffsetMd = 271,
    GridOffsetLg = 272,
    GridOffsetXl = 273,
    GridOrder = 274,
    GridOrderSm = 275,
    GridOrderMd = 276,
    GridOrderLg = 277,
    GridOrderXl = 278,
    GridColumnGap = 279,
    GridRowGap = 280,
    NavigationOperation = 281,
    NavigationTransition = 282,
    NavigationDurationMs = 283,
    NavigationRevision = 284,
    OnClickOutside = 285,
    OnIntersect = 286,
    OnMutate = 287,
    OnResize = 288,
    OnTouchStart = 289,
    OnTouchMove = 290,
    OnTouchEnd = 291,
    DrawerType = 292,
    DrawerWidth = 293,
    DrawerOverlayColor = 294,
    DrawerSwipeEnabled = 295,
    DrawerSwipeEdgeWidth = 296,
    DrawerSwipeMinDistance = 297,
    DrawerKeyboardDismissMode = 298,
    DrawerHideStatusBarOnOpen = 299,
    DrawerStatusBarAnimation = 300,
    DrawerPermanentBreakpoint = 301,
    LayoutDirection = 302,
    GestureType = 303,
    GestureEnabled = 304,
    GestureMinPointers = 305,
    GestureMaxPointers = 306,
    GestureDirection = 307,
    GestureComposition = 308,
    GestureMinDistance = 309,
    GestureMinDurationMs = 310,
    OnGestureBegin = 311,
    OnGestureUpdate = 312,
    OnGestureEnd = 313,
    OnGestureCancel = 314,
    BottomSheetSnapPoints = 315,
    BottomSheetIndex = 316,
    BottomSheetDismissible = 317,
    BottomSheetBackdropDismiss = 318,
    BottomSheetHandleVisible = 319,
    BottomSheetDragEnabled = 320,
    BottomSheetKeyboardBehavior = 321,
    BottomSheetCornerRadius = 322,
    OnBottomSheetChange = 323,
    OnBottomSheetDismiss = 324,
    WebViewSource = 325,
    WebViewJavaScriptEnabled = 326,
    WebViewDomStorageEnabled = 327,
    WebViewUserAgent = 328,
    WebViewInjectedJavaScript = 329,
    WebViewAllowsInlineMedia = 330,
    OnWebViewLoad = 331,
    OnWebViewError = 332,
    OnWebViewMessage = 333,
    MediaSource = 334,
    MediaType = 335,
    MediaAutoPlay = 336,
    MediaControls = 337,
    MediaLoop = 338,
    MediaMuted = 339,
    MediaVolume = 340,
    MediaCurrentTime = 341,
    MediaPlaybackRate = 342,
    OnMediaReady = 343,
    OnMediaProgress = 344,
    OnMediaEnd = 345,
    OnMediaError = 346,
    Draggable = 347,
    DragData = 348,
    DropEnabled = 349,
    ContextMenuItems = 350,
    OnDragStart = 351,
    OnDragEnd = 352,
    OnDrop = 353,
    OnMenuAction = 354,
    NavigationGestureEnabled = 355,
    NavigationGestureEdgeWidth = 356,
    NavigationGestureThreshold = 357,
    OnNavigationGesturePop = 358,
    AnimationKeyframes = 359,
    AnimationIterations = 360,
    AnimationDelayMs = 361,
    AnimationFillMode = 362,
    AnimationPlayState = 363,
    AnimationAutoReverse = 364,
    OnAnimationComplete = 365,
    WebViewAllowedHosts = 366,
    MediaCachePolicy = 367,
    MediaCacheKey = 368,
    MediaCacheMaxAgeMs = 369,
    MediaCacheTags = 370,
    MediaCachePinOffline = 371,
    MediaCacheStreaming = 372,
    MediaCachePreloadSeconds = 373,
    MediaCacheDownloadWhilePlaying = 374,
    MediaCacheMaxBytes = 375,
    MediaThumbnailSource = 376,
    MediaResizeWidth = 377,
    MediaResizeHeight = 378,
    MediaPriority = 379,
    OnMediaCacheHit = 380,
    OnMediaCacheMiss = 381,
    OnMediaCacheProgress = 382,
    OnMediaCacheReady = 383,
    MediaCacheChecksum = 384,
    ScrollAnchorToEnd = 385,
    ScrollMaintainVisibleContentPosition = 386,
    ScrollAutoScrollToEndThreshold = 387,
    ScrollTargetTestId = 388,
    ScrollRequest = 389,
    ScrollTargetOffset = 390,
    DrawingColor = 391,
    DrawingWidth = 392,
    DrawingMode = 393,
    DrawingClearRequest = 394,
    DrawingUndoRequest = 395,
    FlexWrap = 396,
    LeftPercent = 397,
    TopPercent = 398,
    RightPercent = 399,
    BottomPercent = 400,
    ShadowOffsetX = 401,
    ShadowOffsetY = 402,
    ShadowBlurRadius = 403,
    ShadowSpreadRadius = 404,
    ShadowColor = 405,
    GestureNativeTransform = 406,
    GestureNativeMinScale = 407,
    GestureNativeMaxScale = 408,
    GestureNativeResetKey = 409,
    NavigationOrientation = 410,
    NavigationAutoHideHomeIndicator = 411,
}

impl TryFrom<u16> for PropKey {
    type Error = ProtocolError;

    fn try_from(value: u16) -> Result<Self, Self::Error> {
        match value {
            1 => Ok(Self::Text),
            2 => Ok(Self::Value),
            3 => Ok(Self::Placeholder),
            4 => Ok(Self::Source),
            5 => Ok(Self::Width),
            6 => Ok(Self::Height),
            7 => Ok(Self::FlexGrow),
            8 => Ok(Self::Padding),
            9 => Ok(Self::Gap),
            10 => Ok(Self::BackgroundColor),
            11 => Ok(Self::TextColor),
            12 => Ok(Self::FontSize),
            13 => Ok(Self::Enabled),
            14 => Ok(Self::OnPress),
            15 => Ok(Self::OnChange),
            16 => Ok(Self::Items),
            17 => Ok(Self::AccessibilityLabel),
            18 => Ok(Self::TestId),
            19 => Ok(Self::OnLongPress),
            20 => Ok(Self::OnFocus),
            21 => Ok(Self::OnBlur),
            22 => Ok(Self::OnSubmit),
            23 => Ok(Self::OnScroll),
            24 => Ok(Self::OnRefresh),
            25 => Ok(Self::OnToggle),
            26 => Ok(Self::Margin),
            27 => Ok(Self::MarginHorizontal),
            28 => Ok(Self::MarginVertical),
            29 => Ok(Self::PaddingHorizontal),
            30 => Ok(Self::PaddingVertical),
            31 => Ok(Self::MinWidth),
            32 => Ok(Self::MinHeight),
            33 => Ok(Self::MaxWidth),
            34 => Ok(Self::MaxHeight),
            35 => Ok(Self::BorderRadius),
            36 => Ok(Self::BorderWidth),
            37 => Ok(Self::BorderColor),
            38 => Ok(Self::Opacity),
            39 => Ok(Self::AlignItems),
            40 => Ok(Self::AlignSelf),
            41 => Ok(Self::JustifyContent),
            42 => Ok(Self::TextAlign),
            43 => Ok(Self::FontWeight),
            44 => Ok(Self::NumberOfLines),
            45 => Ok(Self::Multiline),
            46 => Ok(Self::Secure),
            47 => Ok(Self::KeyboardType),
            48 => Ok(Self::AutoComplete),
            49 => Ok(Self::InputDebounceMs),
            50 => Ok(Self::InputSyncMode),
            51 => Ok(Self::Checked),
            52 => Ok(Self::Loading),
            53 => Ok(Self::ProgressColor),
            54 => Ok(Self::ImageFit),
            55 => Ok(Self::TintColor),
            56 => Ok(Self::Elevation),
            57 => Ok(Self::Visible),
            58 => Ok(Self::ModalPresentation),
            59 => Ok(Self::StatusBarColor),
            60 => Ok(Self::StatusBarStyle),
            61 => Ok(Self::StatusBarHidden),
            62 => Ok(Self::KeyboardBehavior),
            63 => Ok(Self::Refreshing),
            64 => Ok(Self::ScrollEnabled),
            65 => Ok(Self::ShowsScrollIndicator),
            66 => Ok(Self::Selected),
            67 => Ok(Self::RippleColor),
            68 => Ok(Self::PressOpacity),
            69 => Ok(Self::Collapsable),
            70 => Ok(Self::AccessibilityRole),
            71 => Ok(Self::AccessibilityHint),
            72 => Ok(Self::TranslationX),
            73 => Ok(Self::TranslationY),
            74 => Ok(Self::ScaleX),
            75 => Ok(Self::ScaleY),
            76 => Ok(Self::Rotation),
            77 => Ok(Self::AnimationDurationMs),
            78 => Ok(Self::AnimationEasing),
            79 => Ok(Self::AnimateChanges),
            80 => Ok(Self::SectionItems),
            81 => Ok(Self::ListRowHeight),
            82 => Ok(Self::ListPrefetch),
            83 => Ok(Self::OnEndReached),
            84 => Ok(Self::EndReachedThreshold),
            85 => Ok(Self::DrawerOpen),
            86 => Ok(Self::DrawerPosition),
            87 => Ok(Self::OnDrawerOpen),
            88 => Ok(Self::OnDrawerClose),
            89 => Ok(Self::LetterSpacing),
            90 => Ok(Self::LineHeight),
            91 => Ok(Self::PlaceholderColor),
            92 => Ok(Self::SelectionColor),
            93 => Ok(Self::MaxLength),
            94 => Ok(Self::AutoFocus),
            95 => Ok(Self::ReturnKeyType),
            96 => Ok(Self::HitSlop),
            97 => Ok(Self::ZIndex),
            98 => Ok(Self::Overflow),
            99 => Ok(Self::HostName),
            100 => Ok(Self::HostProperties),
            101 => Ok(Self::OnNativeEvent),
            102 => Ok(Self::FlexDirection),
            103 => Ok(Self::FlexShrink),
            104 => Ok(Self::PaddingLeft),
            105 => Ok(Self::PaddingTop),
            106 => Ok(Self::PaddingRight),
            107 => Ok(Self::PaddingBottom),
            108 => Ok(Self::MarginLeft),
            109 => Ok(Self::MarginTop),
            110 => Ok(Self::MarginRight),
            111 => Ok(Self::MarginBottom),
            112 => Ok(Self::PositionType),
            113 => Ok(Self::Left),
            114 => Ok(Self::Top),
            115 => Ok(Self::Right),
            116 => Ok(Self::Bottom),
            117 => Ok(Self::AspectRatio),
            118 => Ok(Self::BorderTopLeftRadius),
            119 => Ok(Self::BorderTopRightRadius),
            120 => Ok(Self::BorderBottomRightRadius),
            121 => Ok(Self::BorderBottomLeftRadius),
            122 => Ok(Self::BorderLeftWidth),
            123 => Ok(Self::BorderTopWidth),
            124 => Ok(Self::BorderRightWidth),
            125 => Ok(Self::BorderBottomWidth),
            126 => Ok(Self::TextDecoration),
            127 => Ok(Self::TextTransform),
            128 => Ok(Self::FontStyle),
            129 => Ok(Self::WidthPercent),
            130 => Ok(Self::HeightPercent),
            131 => Ok(Self::MaxWidthPercent),
            132 => Ok(Self::MaxHeightPercent),
            133 => Ok(Self::PointerEvents),
            134 => Ok(Self::SafeAreaBottom),
            135 => Ok(Self::BlurRadius),
            136 => Ok(Self::FontFamily),
            137 => Ok(Self::MarginLeftAuto),
            138 => Ok(Self::TranslationXPercent),
            139 => Ok(Self::AnimationKind),
            140 => Ok(Self::Accessible),
            141 => Ok(Self::AccessibilityLiveRegion),
            142 => Ok(Self::AccessibilityImportance),
            143 => Ok(Self::AccessibilityExpanded),
            144 => Ok(Self::AccessibilityBusy),
            145 => Ok(Self::AccessibilityCheckedState),
            146 => Ok(Self::AccessibilityValueMin),
            147 => Ok(Self::AccessibilityValueMax),
            148 => Ok(Self::AccessibilityValueNow),
            149 => Ok(Self::AccessibilityValueText),
            150 => Ok(Self::SafeAreaTop),
            151 => Ok(Self::SafeAreaRight),
            152 => Ok(Self::SafeAreaBottomEdge),
            153 => Ok(Self::SafeAreaLeft),
            154 => Ok(Self::SafeAreaMode),
            155 => Ok(Self::KeyboardVerticalOffset),
            156 => Ok(Self::KeyboardAvoidingEnabled),
            157 => Ok(Self::RefreshColors),
            158 => Ok(Self::RefreshProgressBackgroundColor),
            159 => Ok(Self::RefreshProgressViewOffset),
            160 => Ok(Self::RefreshIndicatorSize),
            161 => Ok(Self::TextSelectable),
            162 => Ok(Self::TextEllipsizeMode),
            163 => Ok(Self::TextAllowFontScaling),
            164 => Ok(Self::TextMaxFontSizeMultiplier),
            165 => Ok(Self::TextAdjustsFontSizeToFit),
            166 => Ok(Self::TextMinimumFontScale),
            167 => Ok(Self::TextBreakStrategy),
            168 => Ok(Self::TextHyphenationFrequency),
            169 => Ok(Self::TextDataDetectorType),
            170 => Ok(Self::StatusBarAnimated),
            171 => Ok(Self::StatusBarTranslucent),
            172 => Ok(Self::ListHorizontal),
            173 => Ok(Self::ListNumColumns),
            174 => Ok(Self::ListInverted),
            175 => Ok(Self::ListInitialScrollIndex),
            176 => Ok(Self::ListRemoveClippedSubviews),
            177 => Ok(Self::ScrollHorizontal),
            178 => Ok(Self::ScrollContentOffsetX),
            179 => Ok(Self::ScrollContentOffsetY),
            180 => Ok(Self::ScrollFillViewport),
            181 => Ok(Self::ScrollOverScrollMode),
            182 => Ok(Self::ScrollNestedEnabled),
            183 => Ok(Self::ScrollFadingEdgeLength),
            184 => Ok(Self::ScrollPersistentScrollbar),
            185 => Ok(Self::ScrollPagingEnabled),
            186 => Ok(Self::ScrollSnapInterval),
            187 => Ok(Self::ScrollDecelerationRate),
            188 => Ok(Self::ScrollKeyboardDismissMode),
            189 => Ok(Self::ActivityAnimating),
            190 => Ok(Self::ActivityHidesWhenStopped),
            191 => Ok(Self::ActivitySize),
            192 => Ok(Self::SwitchTrackColorFalse),
            193 => Ok(Self::SwitchTrackColorTrue),
            194 => Ok(Self::SwitchThumbColor),
            195 => Ok(Self::ImageDefaultSource),
            196 => Ok(Self::ImageLoadingIndicatorSource),
            197 => Ok(Self::ImageFadeDurationMs),
            198 => Ok(Self::ImageResizeMethod),
            199 => Ok(Self::ImageResizeMultiplier),
            200 => Ok(Self::ImageProgressiveRenderingEnabled),
            201 => Ok(Self::ImageCachePolicy),
            202 => Ok(Self::ImageOverlayColor),
            203 => Ok(Self::ImageSourceSet),
            204 => Ok(Self::ImageRequestHeaders),
            205 => Ok(Self::OnImageLoadStart),
            206 => Ok(Self::OnImageProgress),
            207 => Ok(Self::OnImageLoad),
            208 => Ok(Self::OnImageError),
            209 => Ok(Self::OnImageLoadEnd),
            210 => Ok(Self::InputEditable),
            211 => Ok(Self::InputAutoCorrect),
            212 => Ok(Self::InputAutoCapitalize),
            213 => Ok(Self::InputCaretHidden),
            214 => Ok(Self::InputContextMenuHidden),
            215 => Ok(Self::InputCursorColor),
            216 => Ok(Self::InputDisableFullscreenUi),
            217 => Ok(Self::InputAutofillImportance),
            218 => Ok(Self::InputMode),
            219 => Ok(Self::InputMinLines),
            220 => Ok(Self::InputSelectTextOnFocus),
            221 => Ok(Self::InputSelectionStart),
            222 => Ok(Self::InputSelectionEnd),
            223 => Ok(Self::InputShowSoftInputOnFocus),
            224 => Ok(Self::InputSubmitBehavior),
            225 => Ok(Self::InputTextAlignVertical),
            226 => Ok(Self::InputReturnKeyLabel),
            227 => Ok(Self::InputScrollEnabled),
            228 => Ok(Self::InputUnderlineColor),
            229 => Ok(Self::OnInputEndEditing),
            230 => Ok(Self::OnInputSelectionChange),
            231 => Ok(Self::OnInputContentSizeChange),
            232 => Ok(Self::OnInputKeyPress),
            233 => Ok(Self::HitSlopLeft),
            234 => Ok(Self::HitSlopTop),
            235 => Ok(Self::HitSlopRight),
            236 => Ok(Self::HitSlopBottom),
            237 => Ok(Self::PressRetentionLeft),
            238 => Ok(Self::PressRetentionTop),
            239 => Ok(Self::PressRetentionRight),
            240 => Ok(Self::PressRetentionBottom),
            241 => Ok(Self::PressDelayLongMs),
            242 => Ok(Self::PressDelayInMs),
            243 => Ok(Self::PressDelayOutMs),
            244 => Ok(Self::PressAndroidDisableSound),
            245 => Ok(Self::RippleBorderless),
            246 => Ok(Self::RippleRadius),
            247 => Ok(Self::RippleForeground),
            248 => Ok(Self::RippleAlpha),
            249 => Ok(Self::OnPressIn),
            250 => Ok(Self::OnPressOut),
            251 => Ok(Self::OnPressMove),
            252 => Ok(Self::ModalAnimationType),
            253 => Ok(Self::ModalBackdropColor),
            254 => Ok(Self::ModalTransparent),
            255 => Ok(Self::ModalHardwareAccelerated),
            256 => Ok(Self::ModalNavigationBarTranslucent),
            257 => Ok(Self::ModalStatusBarTranslucent),
            258 => Ok(Self::ModalAllowSwipeDismissal),
            259 => Ok(Self::OnModalRequestClose),
            260 => Ok(Self::OnModalShow),
            261 => Ok(Self::OnModalDismiss),
            262 => Ok(Self::OnModalOrientationChange),
            263 => Ok(Self::GridColumns),
            264 => Ok(Self::GridSpan),
            265 => Ok(Self::GridSpanSm),
            266 => Ok(Self::GridSpanMd),
            267 => Ok(Self::GridSpanLg),
            268 => Ok(Self::GridSpanXl),
            269 => Ok(Self::GridOffset),
            270 => Ok(Self::GridOffsetSm),
            271 => Ok(Self::GridOffsetMd),
            272 => Ok(Self::GridOffsetLg),
            273 => Ok(Self::GridOffsetXl),
            274 => Ok(Self::GridOrder),
            275 => Ok(Self::GridOrderSm),
            276 => Ok(Self::GridOrderMd),
            277 => Ok(Self::GridOrderLg),
            278 => Ok(Self::GridOrderXl),
            279 => Ok(Self::GridColumnGap),
            280 => Ok(Self::GridRowGap),
            281 => Ok(Self::NavigationOperation),
            282 => Ok(Self::NavigationTransition),
            283 => Ok(Self::NavigationDurationMs),
            284 => Ok(Self::NavigationRevision),
            285 => Ok(Self::OnClickOutside),
            286 => Ok(Self::OnIntersect),
            287 => Ok(Self::OnMutate),
            288 => Ok(Self::OnResize),
            289 => Ok(Self::OnTouchStart),
            290 => Ok(Self::OnTouchMove),
            291 => Ok(Self::OnTouchEnd),
            292 => Ok(Self::DrawerType),
            293 => Ok(Self::DrawerWidth),
            294 => Ok(Self::DrawerOverlayColor),
            295 => Ok(Self::DrawerSwipeEnabled),
            296 => Ok(Self::DrawerSwipeEdgeWidth),
            297 => Ok(Self::DrawerSwipeMinDistance),
            298 => Ok(Self::DrawerKeyboardDismissMode),
            299 => Ok(Self::DrawerHideStatusBarOnOpen),
            300 => Ok(Self::DrawerStatusBarAnimation),
            301 => Ok(Self::DrawerPermanentBreakpoint),
            302 => Ok(Self::LayoutDirection),
            303 => Ok(Self::GestureType),
            304 => Ok(Self::GestureEnabled),
            305 => Ok(Self::GestureMinPointers),
            306 => Ok(Self::GestureMaxPointers),
            307 => Ok(Self::GestureDirection),
            308 => Ok(Self::GestureComposition),
            309 => Ok(Self::GestureMinDistance),
            310 => Ok(Self::GestureMinDurationMs),
            311 => Ok(Self::OnGestureBegin),
            312 => Ok(Self::OnGestureUpdate),
            313 => Ok(Self::OnGestureEnd),
            314 => Ok(Self::OnGestureCancel),
            315 => Ok(Self::BottomSheetSnapPoints),
            316 => Ok(Self::BottomSheetIndex),
            317 => Ok(Self::BottomSheetDismissible),
            318 => Ok(Self::BottomSheetBackdropDismiss),
            319 => Ok(Self::BottomSheetHandleVisible),
            320 => Ok(Self::BottomSheetDragEnabled),
            321 => Ok(Self::BottomSheetKeyboardBehavior),
            322 => Ok(Self::BottomSheetCornerRadius),
            323 => Ok(Self::OnBottomSheetChange),
            324 => Ok(Self::OnBottomSheetDismiss),
            325 => Ok(Self::WebViewSource),
            326 => Ok(Self::WebViewJavaScriptEnabled),
            327 => Ok(Self::WebViewDomStorageEnabled),
            328 => Ok(Self::WebViewUserAgent),
            329 => Ok(Self::WebViewInjectedJavaScript),
            330 => Ok(Self::WebViewAllowsInlineMedia),
            331 => Ok(Self::OnWebViewLoad),
            332 => Ok(Self::OnWebViewError),
            333 => Ok(Self::OnWebViewMessage),
            334 => Ok(Self::MediaSource),
            335 => Ok(Self::MediaType),
            336 => Ok(Self::MediaAutoPlay),
            337 => Ok(Self::MediaControls),
            338 => Ok(Self::MediaLoop),
            339 => Ok(Self::MediaMuted),
            340 => Ok(Self::MediaVolume),
            341 => Ok(Self::MediaCurrentTime),
            342 => Ok(Self::MediaPlaybackRate),
            343 => Ok(Self::OnMediaReady),
            344 => Ok(Self::OnMediaProgress),
            345 => Ok(Self::OnMediaEnd),
            346 => Ok(Self::OnMediaError),
            347 => Ok(Self::Draggable),
            348 => Ok(Self::DragData),
            349 => Ok(Self::DropEnabled),
            350 => Ok(Self::ContextMenuItems),
            351 => Ok(Self::OnDragStart),
            352 => Ok(Self::OnDragEnd),
            353 => Ok(Self::OnDrop),
            354 => Ok(Self::OnMenuAction),
            355 => Ok(Self::NavigationGestureEnabled),
            356 => Ok(Self::NavigationGestureEdgeWidth),
            357 => Ok(Self::NavigationGestureThreshold),
            358 => Ok(Self::OnNavigationGesturePop),
            359 => Ok(Self::AnimationKeyframes),
            360 => Ok(Self::AnimationIterations),
            361 => Ok(Self::AnimationDelayMs),
            362 => Ok(Self::AnimationFillMode),
            363 => Ok(Self::AnimationPlayState),
            364 => Ok(Self::AnimationAutoReverse),
            365 => Ok(Self::OnAnimationComplete),
            366 => Ok(Self::WebViewAllowedHosts),
            367 => Ok(Self::MediaCachePolicy),
            368 => Ok(Self::MediaCacheKey),
            369 => Ok(Self::MediaCacheMaxAgeMs),
            370 => Ok(Self::MediaCacheTags),
            371 => Ok(Self::MediaCachePinOffline),
            372 => Ok(Self::MediaCacheStreaming),
            373 => Ok(Self::MediaCachePreloadSeconds),
            374 => Ok(Self::MediaCacheDownloadWhilePlaying),
            375 => Ok(Self::MediaCacheMaxBytes),
            376 => Ok(Self::MediaThumbnailSource),
            377 => Ok(Self::MediaResizeWidth),
            378 => Ok(Self::MediaResizeHeight),
            379 => Ok(Self::MediaPriority),
            380 => Ok(Self::OnMediaCacheHit),
            381 => Ok(Self::OnMediaCacheMiss),
            382 => Ok(Self::OnMediaCacheProgress),
            383 => Ok(Self::OnMediaCacheReady),
            384 => Ok(Self::MediaCacheChecksum),
            385 => Ok(Self::ScrollAnchorToEnd),
            386 => Ok(Self::ScrollMaintainVisibleContentPosition),
            387 => Ok(Self::ScrollAutoScrollToEndThreshold),
            388 => Ok(Self::ScrollTargetTestId),
            389 => Ok(Self::ScrollRequest),
            390 => Ok(Self::ScrollTargetOffset),
            391 => Ok(Self::DrawingColor),
            392 => Ok(Self::DrawingWidth),
            393 => Ok(Self::DrawingMode),
            394 => Ok(Self::DrawingClearRequest),
            395 => Ok(Self::DrawingUndoRequest),
            396 => Ok(Self::FlexWrap),
            397 => Ok(Self::LeftPercent),
            398 => Ok(Self::TopPercent),
            399 => Ok(Self::RightPercent),
            400 => Ok(Self::BottomPercent),
            401 => Ok(Self::ShadowOffsetX),
            402 => Ok(Self::ShadowOffsetY),
            403 => Ok(Self::ShadowBlurRadius),
            404 => Ok(Self::ShadowSpreadRadius),
            405 => Ok(Self::ShadowColor),
            406 => Ok(Self::GestureNativeTransform),
            407 => Ok(Self::GestureNativeMinScale),
            408 => Ok(Self::GestureNativeMaxScale),
            409 => Ok(Self::GestureNativeResetKey),
            410 => Ok(Self::NavigationOrientation),
            411 => Ok(Self::NavigationAutoHideHomeIndicator),
            other => Err(ProtocolError::UnknownProperty(other)),
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub enum PropValue {
    String(String),
    Integer(i64),
    Float(f64),
    Boolean(bool),
    Bytes(Vec<u8>),
}

impl PropValue {
    #[must_use]
    pub fn as_number(&self) -> Option<f32> {
        match self {
            Self::Integer(value) => Some(*value as f32),
            Self::Float(value) => Some(*value as f32),
            _ => None,
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Node {
    pub id: u64,
    pub parent: u64,
    pub index: u32,
    pub kind: NodeKind,
    pub properties: BTreeMap<PropKey, PropValue>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Tree {
    pub root: u64,
    pub nodes: BTreeMap<u64, Node>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PropertyPatch {
    pub id: u64,
    pub key: PropKey,
    pub value: Option<PropValue>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum PatchOperation {
    Create(Node),
    Remove { id: u64 },
    Update(PropertyPatch),
    Move { id: u64, parent: u64, index: u32 },
    SetRoot { id: u64 },
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Patch {
    pub operations: Vec<PatchOperation>,
}

impl Patch {
    pub fn decode(frame: &[u8]) -> Result<Self, ProtocolError> {
        if frame.len() > MAX_FRAME_BYTES {
            return Err(ProtocolError::LimitExceeded("patch bytes"));
        }
        let mut reader = Reader::new(frame);
        if reader.bytes(4)? != PATCH_MAGIC {
            return Err(ProtocolError::InvalidMagic);
        }
        let version = reader.u16()?;
        if version != PROTOCOL_VERSION {
            return Err(ProtocolError::UnsupportedVersion(version));
        }
        let count = reader.u32()? as usize;
        if count > MAX_NODES.saturating_mul(MAX_PROPERTIES_PER_NODE) {
            return Err(ProtocolError::LimitExceeded("patch operation count"));
        }
        let mut seen = BTreeSet::new();
        let mut operations = Vec::with_capacity(count);
        for _ in 0..count {
            let operation = reader.u8()?;
            operations.push(match operation {
                1 => {
                    let node = decode_node(&mut reader)?;
                    validate_id(node.id)?;
                    PatchOperation::Create(node)
                }
                2 => PatchOperation::Remove {
                    id: positive_id(&mut reader)?,
                },
                3 => {
                    let id = positive_id(&mut reader)?;
                    let key = PropKey::try_from(reader.u16()?)?;
                    if !seen.insert((id, key)) {
                        return Err(ProtocolError::DuplicatePatchProperty {
                            node: id,
                            property: key as u16,
                        });
                    }
                    let value = match reader.u8()? {
                        1 => Some(decode_value(&mut reader)?),
                        2 => None,
                        other => return Err(ProtocolError::UnknownValueTag(other)),
                    };
                    PatchOperation::Update(PropertyPatch { id, key, value })
                }
                4 => PatchOperation::Move {
                    id: positive_id(&mut reader)?,
                    parent: reader.u64()?,
                    index: reader.u32()?,
                },
                5 => PatchOperation::SetRoot {
                    id: positive_id(&mut reader)?,
                },
                other => return Err(ProtocolError::UnknownPatchOperation(other)),
            });
        }
        reader.finish()?;
        Ok(Self { operations })
    }

    pub fn encode(&self) -> Result<Vec<u8>, ProtocolError> {
        let mut writer = Writer::with_capacity(self.operations.len().saturating_mul(32));
        writer.bytes(&PATCH_MAGIC);
        writer.u16(PROTOCOL_VERSION);
        writer.u32(usize_to_u32(self.operations.len())?);
        let mut seen = BTreeSet::new();
        for operation in &self.operations {
            match operation {
                PatchOperation::Create(node) => {
                    validate_id(node.id)?;
                    writer.u8(1);
                    encode_node(&mut writer, node)?;
                }
                PatchOperation::Remove { id } => {
                    validate_id(*id)?;
                    writer.u8(2);
                    writer.u64(*id);
                }
                PatchOperation::Update(update) => {
                    validate_id(update.id)?;
                    if !seen.insert((update.id, update.key)) {
                        return Err(ProtocolError::DuplicatePatchProperty {
                            node: update.id,
                            property: update.key as u16,
                        });
                    }
                    writer.u8(3);
                    writer.u64(update.id);
                    writer.u16(update.key as u16);
                    match &update.value {
                        Some(value) => {
                            writer.u8(1);
                            encode_value(&mut writer, value)?;
                        }
                        None => writer.u8(2),
                    }
                }
                PatchOperation::Move { id, parent, index } => {
                    validate_id(*id)?;
                    writer.u8(4);
                    writer.u64(*id);
                    writer.u64(*parent);
                    writer.u32(*index);
                }
                PatchOperation::SetRoot { id } => {
                    validate_id(*id)?;
                    writer.u8(5);
                    writer.u64(*id);
                }
            }
        }
        writer.finish()
    }

    #[must_use]
    pub fn is_property_only(&self) -> bool {
        self.operations
            .iter()
            .all(|operation| matches!(operation, PatchOperation::Update(_)))
    }
}

impl Tree {
    pub fn decode(frame: &[u8]) -> Result<Self, ProtocolError> {
        if frame.len() > MAX_FRAME_BYTES {
            return Err(ProtocolError::LimitExceeded("frame bytes"));
        }
        let mut reader = Reader::new(frame);
        if reader.bytes(4)? != TREE_MAGIC {
            return Err(ProtocolError::InvalidMagic);
        }
        let version = reader.u16()?;
        if version != PROTOCOL_VERSION {
            return Err(ProtocolError::UnsupportedVersion(version));
        }
        let root = reader.u64()?;
        let count = reader.u32()? as usize;
        if count == 0 || count > MAX_NODES {
            return Err(ProtocolError::LimitExceeded("node count"));
        }
        let mut nodes = BTreeMap::new();
        for _ in 0..count {
            let node = decode_node(&mut reader)?;
            let id = node.id;
            if id == 0 {
                return Err(ProtocolError::ZeroNodeId);
            }
            if nodes.insert(id, node).is_some() {
                return Err(ProtocolError::DuplicateNode(id));
            }
        }
        reader.finish()?;
        let tree = Self { root, nodes };
        tree.validate()?;
        Ok(tree)
    }

    pub fn encode(&self) -> Result<Vec<u8>, ProtocolError> {
        self.validate()?;
        let mut writer = Writer::with_capacity(self.nodes.len().saturating_mul(64));
        writer.bytes(&TREE_MAGIC);
        writer.u16(PROTOCOL_VERSION);
        writer.u64(self.root);
        writer.u32(usize_to_u32(self.nodes.len())?);
        for node in self.nodes.values() {
            encode_node(&mut writer, node)?;
        }
        writer.finish()
    }

    pub fn validate(&self) -> Result<(), ProtocolError> {
        if self.nodes.is_empty() || self.nodes.len() > MAX_NODES {
            return Err(ProtocolError::LimitExceeded("node count"));
        }
        let root = self
            .nodes
            .get(&self.root)
            .ok_or(ProtocolError::MissingRoot(self.root))?;
        if root.parent != 0 {
            return Err(ProtocolError::RootHasParent);
        }

        let mut sibling_positions = BTreeSet::new();
        for node in self.nodes.values() {
            validate_node(node)?;
            if node.id != self.root {
                if node.parent == 0 || !self.nodes.contains_key(&node.parent) {
                    return Err(ProtocolError::MissingParent {
                        node: node.id,
                        parent: node.parent,
                    });
                }
                if !sibling_positions.insert((node.parent, node.index)) {
                    return Err(ProtocolError::DuplicateSiblingIndex {
                        parent: node.parent,
                        index: node.index,
                    });
                }
            }
        }

        #[derive(Clone, Copy, Eq, PartialEq)]
        enum Visit {
            Visiting,
            Valid,
        }

        let mut visits = BTreeMap::from([(self.root, Visit::Valid)]);
        let mut depths = BTreeMap::from([(self.root, 0_usize)]);
        for start in self.nodes.keys().copied() {
            if visits.get(&start) == Some(&Visit::Valid) {
                continue;
            }
            let mut cursor = start;
            let mut path = Vec::new();
            loop {
                match visits.get(&cursor).copied() {
                    Some(Visit::Valid) => break,
                    Some(Visit::Visiting) => return Err(ProtocolError::Cycle(start)),
                    None => {
                        visits.insert(cursor, Visit::Visiting);
                        path.push(cursor);
                        cursor = self
                            .nodes
                            .get(&cursor)
                            .ok_or(ProtocolError::Disconnected(start))?
                            .parent;
                    }
                }
            }
            let mut depth = *depths
                .get(&cursor)
                .ok_or(ProtocolError::Disconnected(start))?;
            for id in path.into_iter().rev() {
                depth = depth.saturating_add(1);
                if depth > MAX_TREE_DEPTH {
                    return Err(ProtocolError::LimitExceeded("tree depth"));
                }
                visits.insert(id, Visit::Valid);
                depths.insert(id, depth);
            }
        }
        Ok(())
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Layout {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Mutation {
    Create(Node),
    Remove {
        id: u64,
    },
    Update {
        id: u64,
        key: PropKey,
        value: Option<PropValue>,
    },
    Move {
        id: u64,
        parent: u64,
        index: u32,
    },
    Layout {
        id: u64,
        frame: Layout,
    },
    SetRoot {
        id: u64,
    },
}

pub fn encode_batch(mutations: &[Mutation]) -> Result<Vec<u8>, ProtocolError> {
    let mut writer = Writer::with_capacity(mutations.len().saturating_mul(40));
    writer.bytes(&BATCH_MAGIC);
    writer.u16(PROTOCOL_VERSION);
    writer.u32(usize_to_u32(mutations.len())?);
    for mutation in mutations {
        match mutation {
            Mutation::Create(node) => {
                writer.u8(1);
                encode_node(&mut writer, node)?;
            }
            Mutation::Remove { id } => {
                writer.u8(2);
                writer.u64(*id);
            }
            Mutation::Update { id, key, value } => {
                writer.u8(3);
                writer.u64(*id);
                writer.u16(*key as u16);
                match value {
                    Some(value) => {
                        writer.u8(1);
                        encode_value(&mut writer, value)?;
                    }
                    None => writer.u8(2),
                }
            }
            Mutation::Move { id, parent, index } => {
                writer.u8(4);
                writer.u64(*id);
                writer.u64(*parent);
                writer.u32(*index);
            }
            Mutation::Layout { id, frame } => {
                writer.u8(5);
                writer.u64(*id);
                writer.f32(frame.x);
                writer.f32(frame.y);
                writer.f32(frame.width);
                writer.f32(frame.height);
            }
            Mutation::SetRoot { id } => {
                writer.u8(6);
                writer.u64(*id);
            }
        }
    }
    writer.finish()
}

pub fn decode_batch(frame: &[u8]) -> Result<Vec<Mutation>, ProtocolError> {
    if frame.len() > MAX_FRAME_BYTES {
        return Err(ProtocolError::LimitExceeded("batch bytes"));
    }
    let mut reader = Reader::new(frame);
    if reader.bytes(4)? != BATCH_MAGIC {
        return Err(ProtocolError::InvalidMagic);
    }
    let version = reader.u16()?;
    if version != PROTOCOL_VERSION {
        return Err(ProtocolError::UnsupportedVersion(version));
    }
    let count = reader.u32()? as usize;
    if count > MAX_NODES.saturating_mul(8) {
        return Err(ProtocolError::LimitExceeded("mutation count"));
    }
    let mut mutations = Vec::with_capacity(count);
    for _ in 0..count {
        mutations.push(match reader.u8()? {
            1 => Mutation::Create(decode_node(&mut reader)?),
            2 => Mutation::Remove { id: reader.u64()? },
            3 => {
                let id = reader.u64()?;
                let key = PropKey::try_from(reader.u16()?)?;
                let value = match reader.u8()? {
                    1 => Some(decode_value(&mut reader)?),
                    2 => None,
                    other => return Err(ProtocolError::UnknownValueTag(other)),
                };
                Mutation::Update { id, key, value }
            }
            4 => Mutation::Move {
                id: reader.u64()?,
                parent: reader.u64()?,
                index: reader.u32()?,
            },
            5 => Mutation::Layout {
                id: reader.u64()?,
                frame: Layout {
                    x: reader.f32()?,
                    y: reader.f32()?,
                    width: reader.f32()?,
                    height: reader.f32()?,
                },
            },
            6 => Mutation::SetRoot { id: reader.u64()? },
            other => return Err(ProtocolError::UnknownMutation(other)),
        });
    }
    reader.finish()?;
    Ok(mutations)
}

fn validate_node(node: &Node) -> Result<(), ProtocolError> {
    if node.properties.len() > MAX_PROPERTIES_PER_NODE {
        return Err(ProtocolError::LimitExceeded("properties per node"));
    }
    for value in node.properties.values() {
        let bytes = match value {
            PropValue::String(value) => value.len(),
            PropValue::Bytes(value) => value.len(),
            _ => 0,
        };
        if bytes > MAX_VALUE_BYTES {
            return Err(ProtocolError::LimitExceeded("property bytes"));
        }
    }
    Ok(())
}

fn encode_node(writer: &mut Writer, node: &Node) -> Result<(), ProtocolError> {
    validate_node(node)?;
    writer.u64(node.id);
    writer.u64(node.parent);
    writer.u32(node.index);
    writer.u8(node.kind as u8);
    writer.u16(usize_to_u16(node.properties.len())?);
    for (key, value) in &node.properties {
        writer.u16(*key as u16);
        encode_value(writer, value)?;
    }
    Ok(())
}

fn decode_node(reader: &mut Reader<'_>) -> Result<Node, ProtocolError> {
    let id = reader.u64()?;
    let parent = reader.u64()?;
    let index = reader.u32()?;
    let kind = NodeKind::try_from(reader.u8()?)?;
    let property_count = reader.u16()? as usize;
    if property_count > MAX_PROPERTIES_PER_NODE {
        return Err(ProtocolError::LimitExceeded("properties per node"));
    }
    let mut properties = BTreeMap::new();
    for _ in 0..property_count {
        let key = PropKey::try_from(reader.u16()?)?;
        let value = decode_value(reader)?;
        if properties.insert(key, value).is_some() {
            return Err(ProtocolError::DuplicateProperty(key as u16));
        }
    }
    let node = Node {
        id,
        parent,
        index,
        kind,
        properties,
    };
    validate_node(&node)?;
    Ok(node)
}

fn positive_id(reader: &mut Reader<'_>) -> Result<u64, ProtocolError> {
    let id = reader.u64()?;
    validate_id(id)?;
    Ok(id)
}

fn validate_id(id: u64) -> Result<(), ProtocolError> {
    if id == 0 {
        Err(ProtocolError::ZeroNodeId)
    } else {
        Ok(())
    }
}

fn encode_value(writer: &mut Writer, value: &PropValue) -> Result<(), ProtocolError> {
    match value {
        PropValue::String(value) => {
            writer.u8(1);
            writer.sized_bytes(value.as_bytes())?;
        }
        PropValue::Integer(value) => {
            writer.u8(2);
            writer.i64(*value);
        }
        PropValue::Float(value) => {
            writer.u8(3);
            writer.f64(*value);
        }
        PropValue::Boolean(value) => {
            writer.u8(4);
            writer.u8(u8::from(*value));
        }
        PropValue::Bytes(value) => {
            writer.u8(5);
            writer.sized_bytes(value)?;
        }
    }
    Ok(())
}

fn decode_value(reader: &mut Reader<'_>) -> Result<PropValue, ProtocolError> {
    match reader.u8()? {
        1 => {
            let bytes = reader.sized_bytes()?;
            let value = std::str::from_utf8(bytes).map_err(|_| ProtocolError::InvalidUtf8)?;
            Ok(PropValue::String(value.to_owned()))
        }
        2 => Ok(PropValue::Integer(reader.i64()?)),
        3 => Ok(PropValue::Float(reader.f64()?)),
        4 => match reader.u8()? {
            0 => Ok(PropValue::Boolean(false)),
            1 => Ok(PropValue::Boolean(true)),
            _ => Err(ProtocolError::InvalidBoolean),
        },
        5 => Ok(PropValue::Bytes(reader.sized_bytes()?.to_vec())),
        other => Err(ProtocolError::UnknownValueTag(other)),
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ProtocolError {
    InvalidMagic,
    UnsupportedVersion(u16),
    UnexpectedEnd,
    TrailingBytes,
    InvalidUtf8,
    InvalidBoolean,
    UnknownNodeKind(u8),
    UnknownProperty(u16),
    UnknownValueTag(u8),
    UnknownPatchOperation(u8),
    UnknownMutation(u8),
    DuplicateNode(u64),
    DuplicateProperty(u16),
    DuplicatePatchProperty { node: u64, property: u16 },
    DuplicateSiblingIndex { parent: u64, index: u32 },
    ZeroNodeId,
    MissingRoot(u64),
    RootHasParent,
    MissingParent { node: u64, parent: u64 },
    Cycle(u64),
    Disconnected(u64),
    LimitExceeded(&'static str),
    IntegerOverflow,
}

impl fmt::Display for ProtocolError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidMagic => formatter.write_str("invalid Pam Native frame magic"),
            Self::UnsupportedVersion(version) => {
                write!(
                    formatter,
                    "unsupported Pam Native protocol version {version}"
                )
            }
            Self::UnexpectedEnd => formatter.write_str("truncated Pam Native frame"),
            Self::TrailingBytes => formatter.write_str("trailing bytes in Pam Native frame"),
            Self::InvalidUtf8 => formatter.write_str("string property is not valid UTF-8"),
            Self::InvalidBoolean => formatter.write_str("boolean property is neither 0 nor 1"),
            Self::UnknownNodeKind(kind) => write!(formatter, "unknown node kind {kind}"),
            Self::UnknownProperty(property) => write!(formatter, "unknown property {property}"),
            Self::UnknownValueTag(tag) => write!(formatter, "unknown property value tag {tag}"),
            Self::UnknownPatchOperation(kind) => {
                write!(formatter, "unknown patch operation {kind}")
            }
            Self::UnknownMutation(kind) => write!(formatter, "unknown mutation kind {kind}"),
            Self::DuplicateNode(id) => write!(formatter, "duplicate node id {id}"),
            Self::DuplicateProperty(key) => write!(formatter, "duplicate property {key}"),
            Self::DuplicatePatchProperty { node, property } => {
                write!(
                    formatter,
                    "duplicate patch property {property} for node {node}"
                )
            }
            Self::DuplicateSiblingIndex { parent, index } => {
                write!(
                    formatter,
                    "duplicate child index {index} under parent {parent}"
                )
            }
            Self::ZeroNodeId => formatter.write_str("node id 0 is reserved"),
            Self::MissingRoot(id) => write!(formatter, "root node {id} does not exist"),
            Self::RootHasParent => formatter.write_str("root node cannot have a parent"),
            Self::MissingParent { node, parent } => {
                write!(formatter, "node {node} references missing parent {parent}")
            }
            Self::Cycle(id) => write!(formatter, "cycle detected from node {id}"),
            Self::Disconnected(id) => write!(formatter, "node {id} is disconnected from the root"),
            Self::LimitExceeded(limit) => write!(formatter, "protocol limit exceeded: {limit}"),
            Self::IntegerOverflow => formatter.write_str("integer does not fit protocol field"),
        }
    }
}

impl std::error::Error for ProtocolError {}

struct Reader<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn bytes(&mut self, length: usize) -> Result<&'a [u8], ProtocolError> {
        let end = self
            .offset
            .checked_add(length)
            .ok_or(ProtocolError::IntegerOverflow)?;
        let value = self
            .bytes
            .get(self.offset..end)
            .ok_or(ProtocolError::UnexpectedEnd)?;
        self.offset = end;
        Ok(value)
    }

    fn sized_bytes(&mut self) -> Result<&'a [u8], ProtocolError> {
        let length = self.u32()? as usize;
        if length > MAX_VALUE_BYTES {
            return Err(ProtocolError::LimitExceeded("property bytes"));
        }
        self.bytes(length)
    }

    fn u8(&mut self) -> Result<u8, ProtocolError> {
        Ok(self.bytes(1)?[0])
    }

    fn u16(&mut self) -> Result<u16, ProtocolError> {
        Ok(u16::from_le_bytes(
            self.bytes(2)?.try_into().expect("fixed length"),
        ))
    }

    fn u32(&mut self) -> Result<u32, ProtocolError> {
        Ok(u32::from_le_bytes(
            self.bytes(4)?.try_into().expect("fixed length"),
        ))
    }

    fn u64(&mut self) -> Result<u64, ProtocolError> {
        Ok(u64::from_le_bytes(
            self.bytes(8)?.try_into().expect("fixed length"),
        ))
    }

    fn i64(&mut self) -> Result<i64, ProtocolError> {
        Ok(i64::from_le_bytes(
            self.bytes(8)?.try_into().expect("fixed length"),
        ))
    }

    fn f32(&mut self) -> Result<f32, ProtocolError> {
        Ok(f32::from_le_bytes(
            self.bytes(4)?.try_into().expect("fixed length"),
        ))
    }

    fn f64(&mut self) -> Result<f64, ProtocolError> {
        Ok(f64::from_le_bytes(
            self.bytes(8)?.try_into().expect("fixed length"),
        ))
    }

    fn finish(self) -> Result<(), ProtocolError> {
        if self.offset == self.bytes.len() {
            Ok(())
        } else {
            Err(ProtocolError::TrailingBytes)
        }
    }
}

struct Writer {
    bytes: Vec<u8>,
}

impl Writer {
    fn with_capacity(capacity: usize) -> Self {
        Self {
            bytes: Vec::with_capacity(capacity.min(MAX_FRAME_BYTES)),
        }
    }

    fn bytes(&mut self, value: &[u8]) {
        self.bytes.extend_from_slice(value);
    }

    fn sized_bytes(&mut self, value: &[u8]) -> Result<(), ProtocolError> {
        if value.len() > MAX_VALUE_BYTES {
            return Err(ProtocolError::LimitExceeded("property bytes"));
        }
        self.u32(usize_to_u32(value.len())?);
        self.bytes(value);
        Ok(())
    }

    fn u8(&mut self, value: u8) {
        self.bytes.push(value);
    }

    fn u16(&mut self, value: u16) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn u32(&mut self, value: u32) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn u64(&mut self, value: u64) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn i64(&mut self, value: i64) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn f32(&mut self, value: f32) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn f64(&mut self, value: f64) {
        self.bytes.extend_from_slice(&value.to_le_bytes());
    }

    fn finish(self) -> Result<Vec<u8>, ProtocolError> {
        if self.bytes.len() > MAX_FRAME_BYTES {
            Err(ProtocolError::LimitExceeded("frame bytes"))
        } else {
            Ok(self.bytes)
        }
    }
}

fn usize_to_u16(value: usize) -> Result<u16, ProtocolError> {
    u16::try_from(value).map_err(|_| ProtocolError::IntegerOverflow)
}

fn usize_to_u32(value: usize) -> Result<u32, ProtocolError> {
    u32::try_from(value).map_err(|_| ProtocolError::IntegerOverflow)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protocol_enums_are_sequential_and_append_only() {
        for value in 1..=29 {
            assert!(
                NodeKind::try_from(value).is_ok(),
                "missing node kind {value}"
            );
        }
        assert!(NodeKind::try_from(30).is_err());

        for value in 1..=411 {
            assert!(PropKey::try_from(value).is_ok(), "missing property {value}");
        }
        assert!(PropKey::try_from(412).is_err());
    }

    fn tree(text: &str) -> Tree {
        Tree {
            root: 1,
            nodes: BTreeMap::from([
                (
                    1,
                    Node {
                        id: 1,
                        parent: 0,
                        index: 0,
                        kind: NodeKind::Screen,
                        properties: BTreeMap::new(),
                    },
                ),
                (
                    2,
                    Node {
                        id: 2,
                        parent: 1,
                        index: 0,
                        kind: NodeKind::Text,
                        properties: BTreeMap::from([(
                            PropKey::Text,
                            PropValue::String(text.to_owned()),
                        )]),
                    },
                ),
            ]),
        }
    }

    #[test]
    fn tree_round_trip_is_lossless() {
        let original = tree("Pam Native");
        let encoded = original.encode().expect("encode");
        assert_eq!(Tree::decode(&encoded).expect("decode"), original);
    }

    #[test]
    fn protocol_v1_golden_frames_are_stable() {
        let minimal_tree = Tree {
            root: 1,
            nodes: BTreeMap::from([(
                1,
                Node {
                    id: 1,
                    parent: 0,
                    index: 0,
                    kind: NodeKind::Screen,
                    properties: BTreeMap::new(),
                },
            )]),
        };
        assert_eq!(
            hex(&minimal_tree.encode().expect("tree")),
            "504e543101000100000000000000010000000100000000000000000000000000000000000000010000",
        );

        let patch = Patch {
            operations: vec![PatchOperation::Update(PropertyPatch {
                id: 1,
                key: PropKey::Text,
                value: None,
            })],
        };
        assert_eq!(
            hex(&patch.encode().expect("patch")),
            "504e5031010001000000030100000000000000010002",
        );

        assert_eq!(
            hex(&encode_batch(&[Mutation::SetRoot { id: 1 }]).expect("batch")),
            "504e4231010001000000060100000000000000",
        );
    }

    #[test]
    fn batch_round_trip_is_lossless() {
        let mutations = vec![
            Mutation::SetRoot { id: 1 },
            Mutation::Create(tree("Pam").nodes[&1].clone()),
            Mutation::Layout {
                id: 1,
                frame: Layout {
                    x: 0.0,
                    y: 0.0,
                    width: 360.0,
                    height: 800.0,
                },
            },
        ];
        let encoded = encode_batch(&mutations).expect("encode");
        assert_eq!(decode_batch(&encoded).expect("decode"), mutations);
    }

    #[test]
    fn patch_round_trip_is_lossless() {
        let patch = Patch {
            operations: vec![
                PatchOperation::Update(PropertyPatch {
                    id: 2,
                    key: PropKey::Text,
                    value: Some(PropValue::String("Updated".to_owned())),
                }),
                PatchOperation::Update(PropertyPatch {
                    id: 2,
                    key: PropKey::Enabled,
                    value: None,
                }),
                PatchOperation::Create(Node {
                    id: 3,
                    parent: 1,
                    index: 1,
                    kind: NodeKind::Button,
                    properties: BTreeMap::new(),
                }),
                PatchOperation::Move {
                    id: 2,
                    parent: 1,
                    index: 1,
                },
                PatchOperation::Remove { id: 3 },
            ],
        };
        let encoded = patch.encode().expect("encode");
        assert_eq!(Patch::decode(&encoded).expect("decode"), patch);
    }

    #[test]
    fn rejects_cycles_and_trailing_data() {
        let mut invalid = tree("cycle");
        invalid.nodes.get_mut(&1).expect("root").parent = 2;
        assert_eq!(invalid.validate(), Err(ProtocolError::RootHasParent));

        let mut encoded = tree("extra").encode().expect("encode");
        encoded.push(0);
        assert_eq!(Tree::decode(&encoded), Err(ProtocolError::TrailingBytes));
    }

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|byte| format!("{byte:02x}")).collect()
    }
}
