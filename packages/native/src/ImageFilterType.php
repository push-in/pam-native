<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: ImageFilterType moved to the pushinbr/pam-native-media-editor plugin as
 * \Pam\Native\MediaEditor\ImageFilterType. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\ImageFilterType', 'Pam\\Native\\MediaEditor\\ImageFilterType', 'pushinbr/pam-native-media-editor');
