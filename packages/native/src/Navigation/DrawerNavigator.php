<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Pam\Native\Plugin\MovedToPlugin;

MovedToPlugin::alias(DrawerNavigator::class, \Pam\Native\Drawer\DrawerNavigator::class, 'pushinbr/pam-native-drawer');
