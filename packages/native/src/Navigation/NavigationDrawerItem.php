<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Pam\Native\Plugin\MovedToPlugin;

MovedToPlugin::alias(NavigationDrawerItem::class, \Pam\Native\Drawer\NavigationDrawerItem::class, 'pushinbr/pam-native-drawer');
