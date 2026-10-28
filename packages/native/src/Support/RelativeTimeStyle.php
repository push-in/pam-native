<?php

declare(strict_types=1);

namespace Pam\Native\Support;

use Pam\Native\Plugin\MovedToPlugin;

/** @deprecated Install pushinbr/pam-native-i18n and use Pam\Native\I18n\RelativeTimeStyle. */
MovedToPlugin::alias('Pam\\Native\\Support\\RelativeTimeStyle', 'Pam\\Native\\I18n\\RelativeTimeStyle', 'pushinbr/pam-native-i18n');
