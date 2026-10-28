<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

/*
 * Deprecated since 1.46.0: Contact moved to the pushinbr/pam-native-contacts plugin as
 * \Pam\Native\Contacts\Contact. This shim aliases the old name while the plugin is
 * installed and is removed in 1.47.0.
 */
MovedToPlugin::alias('Pam\\Native\\Contact', 'Pam\\Native\\Contacts\\Contact', 'pushinbr/pam-native-contacts');
