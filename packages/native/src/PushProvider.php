<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Plugin\MovedToPlugin;

// Deprecated in 1.46; removed in 1.47.
MovedToPlugin::alias('Pam\\Native\\PushProvider', 'Pam\\Native\\Notifications\\PushProvider', 'pushinbr/pam-native-notifications');
