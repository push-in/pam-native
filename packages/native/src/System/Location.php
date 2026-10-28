<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: Location moved to the pushinbr/pam-native-location plugin as
 * \Pam\Native\Location\Location. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\System\\Location', 'Pam\\Native\\Location\\Location', 'pushinbr/pam-native-location');
