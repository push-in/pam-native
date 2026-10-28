<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: SensorType moved to the pushinbr/pam-native-sensors plugin as
 * \Pam\Native\Sensors\SensorType. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\SensorType', 'Pam\\Native\\Sensors\\SensorType', 'pushinbr/pam-native-sensors');
