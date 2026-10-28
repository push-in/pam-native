<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: SensorReading moved to the pushinbr/pam-native-sensors plugin as
 * \Pam\Native\Sensors\SensorReading. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\SensorReading', 'Pam\\Native\\Sensors\\SensorReading', 'pushinbr/pam-native-sensors');
