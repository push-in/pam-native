<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: ImageTextLayerStyle moved to the pushinbr/pam-native-media-editor plugin as
 * \Pam\Native\MediaEditor\ImageTextLayerStyle. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\ImageTextLayerStyle', 'Pam\\Native\\MediaEditor\\ImageTextLayerStyle', 'pushinbr/pam-native-media-editor');
