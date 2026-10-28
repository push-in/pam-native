<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: Sensors moved to the pushinbr/pam-native-sensors plugin as
 * \Pam\Native\Sensors\Sensors. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\System\\Sensors', 'Pam\\Native\\Sensors\\Sensors', 'pushinbr/pam-native-sensors');
