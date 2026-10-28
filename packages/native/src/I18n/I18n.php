<?php

declare(strict_types=1);

namespace Pam\Native\I18n;

use Pam\Native\Plugin\MovedToPlugin;

/** @deprecated Install pushinbr/pam-native-i18n and use Pam\Native\I18n\Translator. */
MovedToPlugin::alias('Pam\\Native\\I18n\\I18n', 'Pam\\Native\\I18n\\Translator', 'pushinbr/pam-native-i18n');
