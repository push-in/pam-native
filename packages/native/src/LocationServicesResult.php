<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: LocationServicesResult moved to the pushinbr/pam-native-location plugin as
 * \Pam\Native\Location\LocationServicesResult. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\LocationServicesResult', 'Pam\\Native\\Location\\LocationServicesResult', 'pushinbr/pam-native-location');
