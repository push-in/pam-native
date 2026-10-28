<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: Sms moved to the pushinbr/pam-native-contacts plugin as
 * \Pam\Native\Contacts\Sms. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\System\\Sms', 'Pam\\Native\\Contacts\\Sms', 'pushinbr/pam-native-contacts');
