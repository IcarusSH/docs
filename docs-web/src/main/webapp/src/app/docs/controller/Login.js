'use strict';

/**
 * Login controller.
 */
angular.module('docs').controller('Login', function(Restangular, $scope, $rootScope, $state, $stateParams, $dialog, User, $translate, $uibModal, $location) {
  $scope.codeRequired = false;

  // Get the app configuration
  Restangular.one('app').get().then(function(data) {
    $rootScope.app = data;
    // If the server-side callback redirected back here, show a localized error.
    var err = $location.search().error;
    if (err && err.indexOf('casdoor_') === 0) {
      var title = $translate.instant('login.casdoor_login_failed_title');
      var msg = $translate.instant('login.casdoor_login_failed_message', { code: err });
      var btns = [{result: 'ok', label: $translate.instant('ok'), cssClass: 'btn-primary'}];
      $dialog.messageBox(title, msg, btns);
      // Strip the error param so refresh doesn't re-trigger
      $location.search('error', null);
    }
  });

  // If the user is already authenticated (e.g. just bounced back from an SSO callback
  // that set the auth_token cookie before the redirect), forward them to the home state.
  // We deliberately skip this redirect if an `error` query param is present so that
  // a failed SSO callback can still display its localized error message.
  if (!$location.search().error) {
    User.userInfo().then(function(data) {
      if (!data.anonymous) {
        // Already logged in — send them away from the login page
        if ($stateParams.redirectState && $stateParams.redirectParams) {
          $state.go($stateParams.redirectState, JSON.parse($stateParams.redirectParams))
            .catch(function() {
              $state.go('document.default');
            });
        } else {
          $state.go('document.default');
        }
      }
    });
  }

  // Login as guest
  $scope.loginAsGuest = function() {
    $scope.user = {
      username: 'guest',
      password: ''
    };
    $scope.login();
  };

  // Login via Casdoor SSO. Server returns a 302 to the Casdoor authorization endpoint,
  // which redirects to Casdoor's login page; Casdoor then calls back to /api/casdoor/callback.
  // Pass the current page path as ?return_to so the callback can send us back to where we
  // came from (in dev mode the AngularJS app lives at /<ctx>/src/, not /<ctx>/).
  $scope.loginAsCasdoor = function() {
    var returnTo = encodeURIComponent(window.location.pathname + window.location.search + window.location.hash);
    window.location.href = '../api/casdoor/login?return_to=' + returnTo;
  };

  // Login
  $scope.login = function() {
    User.login($scope.user).then(function() {
      User.userInfo(true).then(function(data) {
        $rootScope.userInfo = data;
      });

      if($stateParams.redirectState !== undefined && $stateParams.redirectParams !== undefined) {
        $state.go($stateParams.redirectState, JSON.parse($stateParams.redirectParams))
          .catch(function() {
            $state.go('document.default');
          });
      } else {
        $state.go('document.default');
      }
    }, function(data) {
      if (data.data.type === 'ValidationCodeRequired') {
        // A TOTP validation code is required to login
        $scope.codeRequired = true;
      } else {
        // Login truly failed
        var title = $translate.instant('login.login_failed_title');
        var msg = $translate.instant('login.login_failed_message');
        var btns = [{result: 'ok', label: $translate.instant('ok'), cssClass: 'btn-primary'}];
        $dialog.messageBox(title, msg, btns);
      }
    });
  };

  // Password lost
  $scope.openPasswordLost = function () {
    $uibModal.open({
      templateUrl: 'partial/docs/passwordlost.html',
      controller: 'ModalPasswordLost'
    }).result.then(function (username) {
      if (username === null) {
        return;
      }

      // Send a password lost email
      Restangular.one('user').post('password_lost', {
        username: username
      }).then(function () {
        var title = $translate.instant('login.password_lost_sent_title');
        var msg = $translate.instant('login.password_lost_sent_message', { username: username });
        var btns = [{result: 'ok', label: $translate.instant('ok'), cssClass: 'btn-primary'}];
        $dialog.messageBox(title, msg, btns);
      }, function () {
        var title = $translate.instant('login.password_lost_error_title');
        var msg = $translate.instant('login.password_lost_error_message');
        var btns = [{result: 'ok', label: $translate.instant('ok'), cssClass: 'btn-primary'}];
        $dialog.messageBox(title, msg, btns);
      });
    });
  };
});