<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Pam\Native\Plugin\MovedToPlugin;

MovedToPlugin::alias(DrawerRegistrar::class, \Pam\Native\Drawer\DrawerRegistrar::class, 'pushinbr/pam-native-drawer');
