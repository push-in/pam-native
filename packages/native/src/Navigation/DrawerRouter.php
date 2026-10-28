<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Pam\Native\Plugin\MovedToPlugin;

MovedToPlugin::alias(DrawerRouter::class, \Pam\Native\Drawer\DrawerRouter::class, 'pushinbr/pam-native-drawer');
