<?php
declare(strict_types=1);
namespace Pam\Native\Notifications;
use Pam\Native\Plugin\MovedToPlugin;
MovedToPlugin::alias(__NAMESPACE__.'\\PushRenderingRule', \Pam\Native\Notifications\Contracts\PushRenderingRule::class, 'pushinbr/pam-native-notifications');
