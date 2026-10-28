<?php

declare(strict_types=1);

namespace Pam\Native\Support;

use Pam\Native\Plugin\MovedToPlugin;

/** @deprecated Install pushinbr/pam-native-i18n and use Pam\Native\I18n\Format. */
MovedToPlugin::alias('Pam\\Native\\Support\\Format', 'Pam\\Native\\I18n\\Format', 'pushinbr/pam-native-i18n');
