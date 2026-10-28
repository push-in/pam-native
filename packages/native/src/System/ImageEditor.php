<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: ImageEditor moved to the pushinbr/pam-native-media-editor plugin as
 * \Pam\Native\MediaEditor\ImageEditor. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\System\\ImageEditor', 'Pam\\Native\\MediaEditor\\ImageEditor', 'pushinbr/pam-native-media-editor');
