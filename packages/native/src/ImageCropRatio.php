<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: ImageCropRatio moved to the pushinbr/pam-native-media-editor plugin as
 * \Pam\Native\MediaEditor\ImageCropRatio. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\ImageCropRatio', 'Pam\\Native\\MediaEditor\\ImageCropRatio', 'pushinbr/pam-native-media-editor');
