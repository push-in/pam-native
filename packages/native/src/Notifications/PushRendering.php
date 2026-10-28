<?php
declare(strict_types=1);
namespace Pam\Native\Notifications;
use Pam\Native\Plugin\MovedToPlugin;
MovedToPlugin::alias(__NAMESPACE__.'\\PushRendering', \Pam\Native\Notifications\Contracts\PushRendering::class, 'pushinbr/pam-native-notifications');
