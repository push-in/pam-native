<?php
declare(strict_types=1);
namespace Pam\Native\Notifications;
use Pam\Native\Plugin\MovedToPlugin;
MovedToPlugin::alias(__NAMESPACE__.'\\ActionEndpoint', \Pam\Native\Notifications\Contracts\ActionEndpoint::class, 'pushinbr/pam-native-notifications');
