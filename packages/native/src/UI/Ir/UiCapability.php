<?php

declare(strict_types=1);

namespace Pam\Native\UI\Ir;

/** Stable, append-only Language 2 capability identifiers. */
enum UiCapability: int
{
    case TypedContracts = 1;
    case InteractionStates = 2;
    case DesignTokens = 3;
    case Recipes = 4;
    case ResponsiveQueries = 5;
    case DeclarativeFlow = 6;
    case StableVirtualization = 7;
    case NativeAnimations = 8;
    case AsyncBranches = 9;
    case AccessibilityDiagnostics = 10;
    case ComposerTags = 11;
    case LanguageTooling = 12;
}
