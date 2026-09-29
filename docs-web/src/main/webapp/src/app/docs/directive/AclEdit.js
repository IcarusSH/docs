'use strict';

/**
 * ACL edit directive.
 *
 * With the hierarchical permission model, each source-target pair has at most one ACL row,
 * whose ACL_PERM_C is one of SEARCHABLE / READ / DOWNLOADABLE / WRITE. Higher levels
 * implicitly include all lower levels, so a single row per user is enough.
 *
 * The UI surfaces the four levels as four checkboxes that cascade:
 *   - ticking any box automatically ticks every lower box (implication)
 *   - unticking any box automatically unticks every higher box (no gaps)
 * On submit we only POST the highest ticked level; the lower levels are implicit.
 */
angular.module('docs').directive('aclEdit', function() {
  return {
    restrict: 'E',
    templateUrl: 'partial/docs/directive.acledit.html',
    replace: true,
    scope: {
      source: '=',
      acls: '=',
      writable: '=',
      creator: '='
    },
    controller: function($scope, Restangular, $q) {
      // Permission levels ordered low -> high
      $scope.PERM_LEVELS = ['SEARCHABLE', 'READ', 'DOWNLOADABLE', 'WRITE'];

      // Initialize the add form with READ as a sensible default (matches old behavior)
      $scope.acl = {
        SEARCHABLE: true,
        READ: true,
        DOWNLOADABLE: false,
        WRITE: false
      };

      /**
       * Enforce the cascade invariant after any checkbox change:
       *   - the ticked set must be a contiguous prefix of PERM_LEVELS
       *   - checking X  -> also tick every level below X
       *   - unchecking X -> also untick every level above X
       */
      $scope.cascadePerm = function(level) {
        var idx = $scope.PERM_LEVELS.indexOf(level);
        if (idx < 0) return;
        if ($scope.acl[level]) {
          // Tick every level at or below this one
          for (var i = 0; i <= idx; i++) {
            $scope.acl[$scope.PERM_LEVELS[i]] = true;
          }
        } else {
          // Untick every level at or above this one
          for (var i = idx; i < $scope.PERM_LEVELS.length; i++) {
            $scope.acl[$scope.PERM_LEVELS[i]] = false;
          }
        }
      };

      /**
       * Watch for ACLs change and group them for easy displaying.
       *
       * Two access shapes are rendered side-by-side: target = USER and target = GROUP. Users
       * can have BOTH their own direct ACL on a document/tag and an inherited one through a
       * group; we show both rows so the admin sees the full picture when auditing.
       */
      $scope.$watch('acls', function(acls) {
        $scope.groupedAcls = _.groupBy(acls || [], function(acl) {
          return acl.id;
        });
      });

      /**
       * Delete an ACL.
       */
      $scope.deleteAcl = function(acl) {
        Restangular.one('acl/' + $scope.source + '/' + acl.perm + '/' + acl.id, null).remove().then(function () {
          $scope.acls = _.reject($scope.acls, function(s) {
            return angular.equals(acl, s);
          });
        });
      };

      /**
       * Add an ACL.
       * Send only the highest ticked level as `perm`; lower levels are implicit.
       */
      $scope.addAcl = function() {
        var highestPerm = null;
        for (var i = $scope.PERM_LEVELS.length - 1; i >= 0; i--) {
          if ($scope.acl[$scope.PERM_LEVELS[i]]) {
            highestPerm = $scope.PERM_LEVELS[i];
            break;
          }
        }
        if (!highestPerm) {
          // Nothing ticked; nothing to add (the backend would reject this anyway).
          return;
        }
        var newAcl = {
          source: $scope.source,
          target: $scope.acl.target.name,
          perm: highestPerm,
          type: $scope.acl.target.type
        };
        Restangular.one('acl').put(newAcl).then(function(acl) {
          if (_.isUndefined(acl.id)) {
            return;
          }
          $scope.acls.push(acl);
          $scope.acls = angular.copy($scope.acls);
        });

        // Reset form to the default (SEARCHABLE + READ ticked)
        $scope.acl = {
          SEARCHABLE: true,
          READ: true,
          DOWNLOADABLE: false,
          WRITE: false
        };
      };

      /**
       * Auto-complete on ACL target.
       */
      $scope.getTargetAclTypeahead = function($viewValue) {
        var deferred = $q.defer();
        Restangular.one('acl/target/search')
          .get({
            search: $viewValue
          }).then(function(data) {
          var output = [];

          // Add the type to use later
          output.push.apply(output,  _.map(data.users, function(user) {
            user.type = 'USER';
            return user;
          }));
          output.push.apply(output, _.map(data.groups, function(group) {
            group.type = 'GROUP';
            return group;
          }));

          // Send the data to the typeahead directive
          deferred.resolve(output, true);
        });
        return deferred.promise;
      };
    },
    link: function(scope, element, attr, ctrl) {
    }
  }
});