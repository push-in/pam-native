<?php

declare(strict_types=1);

namespace Pam\Native\Support;

use Pam\Native\Plugin\MovedToPlugin;

/** @deprecated Install pushinbr/pam-native-i18n and use Pam\Native\I18n\DateStyle. */
MovedToPlugin::alias('Pam\\Native\\Support\\DateStyle', 'Pam\\Native\\I18n\\DateStyle', 'pushinbr/pam-native-i18n');
